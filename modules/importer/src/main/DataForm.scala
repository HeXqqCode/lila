package lila.importer

import cats.data.Validated
import shogi.format.kif.KifParser
import shogi.format.csa.CsaParser
import shogi.format.{ FEN, Forsyth, ParsedNotation, Reader, Tag, TagType }
import shogi.{ Color, Mode, Replay, Status }
import play.api.data._
import play.api.data.Forms._
import scala.util.chaining._

import lila.game._

final class DataForm {

  lazy val importForm = Form(
    mapping(
      "notation" -> nonEmptyText.verifying("invalidNotation", p => checkNotation(p).isValid),
      "analyse"  -> optional(nonEmptyText)
    )(ImportData.apply)(ImportData.unapply)
  )

  def checkNotation(notation: String): Validated[String, Preprocessed] =
    ImportData(notation, none).preprocess(none)
}

private case class TagResult(status: Status, winner: Option[Color])
case class Preprocessed(
    game: NewGame,
    replay: Replay,
    initialFen: Option[FEN],
    parsed: ParsedNotation
)

case class ImportData(notation: String, analyse: Option[String]) {

  private type TagPicker = Tag.type => TagType

  private val maxPlies  = 600
  private val maxLength = 32768 // only for storage

  private def evenIncomplete(result: Reader.Result): Replay =
    result match {
      case Reader.Result.Complete(replay)      => replay
      case Reader.Result.Incomplete(replay, _) => replay
    }

  // csa and kif together
  private def createStatus(termination: String): Status =
    termination match {
      case "" | "投了" | "TORYO"                                                      => Status.Resign
      case "詰み" | "TSUMI"                                                           => Status.Mate
      case "中断" | "CHUDAN"                                                          => Status.Aborted
      case "持将棋" | "千日手" | "JISHOGI" | "SENNICHITE" | "HIKIWAKE"                    => Status.Draw
      case "入玉勝ち" | "KACHI"                                                         => Status.Impasse27
      case "切れ負け" | "TIME-UP" | "TIME_UP"                                           => Status.Outoftime
      case "反則勝ち" | "反則負け" | "ILLEGAL_MOVE" | "+ILLEGAL_ACTION" | "-ILLEGAL_ACTION" => Status.Cheat
      case _                                                                        => Status.UnknownFinish
    }

  private val isCsa: Boolean = {
    val noKifComments = augmentString(notation).linesIterator
      .to(List)
      .map(_.split("#|\\*").headOption.getOrElse("").trim)
      .mkString("\n")
    val lines = augmentString(noKifComments.replace(",", "\n")).linesIterator.to(List)
    lines.exists(l => l.startsWith("PI") || l.startsWith("P1") || CsaParser.moveOrDropRegex.matches(l))
  }

  private def parseNotation: Validated[String, ParsedNotation] =
    if (isCsa)
      CsaParser.full(notation).leftMap("CSA parsing error: " + _)
    else
      KifParser.full(notation).leftMap("KIF parsing error: " + _)

  def preprocess(user: Option[String]): Validated[String, Preprocessed] =
    parseNotation map { parsed =>
      Reader.fullWithParsedMoves(
        parsed,
        parsedMoves => parsedMoves.copy(value = parsedMoves.value take maxPlies)
      ) pipe evenIncomplete pipe { case replay @ Replay(_, _, state) =>
        val initBoard    = parsed.tags.fen.map(_.value) flatMap Forsyth.<< map (_.board)
        val fromPosition = initBoard.nonEmpty && !parsed.tags.fen.contains(FEN(Forsyth.initial))
        val variant = {
          parsed.tags.variant | {
            if (fromPosition) shogi.variant.FromPosition
            else shogi.variant.Standard
          }
        } match {
          case shogi.variant.FromPosition if parsed.tags.fen.isEmpty => shogi.variant.Standard
          case shogi.variant.Standard if fromPosition                => shogi.variant.FromPosition
          case v                                                     => v
        }
        val game = state.copy(situation = state.situation withVariant variant, clock = None)
        val initialFen = parsed.tags.fen.map(_.value) flatMap {
          Forsyth.<<<@(variant, _)
        } map Forsyth.>> map FEN.apply

        val status = createStatus(~parsed.tags(_.Termination).map(_.toUpperCase))

        val date = parsed.tags.anyDate

        def name(whichName: TagPicker, whichRating: TagPicker): String =
          parsed.tags(whichName).fold("?") { n =>
            n + ~parsed.tags(whichRating).map(e => s" (${e take 8})")
          }

        val notationTrunc = notation.take(maxLength)

        val dbGame = Game
          .make(
            shogi = game,
            sentePlayer = Player.make(shogi.Sente, None) withName name(_.Sente, _.SenteElo),
            gotePlayer = Player.make(shogi.Gote, None) withName name(_.Gote, _.GoteElo),
            mode = Mode.Casual,
            source = Source.Import,
            notationImport = NotationImport
              .make(
                user = user,
                date = date,
                kif = !isCsa option notationTrunc,
                csa = isCsa option notationTrunc
              )
              .some
          )
          .sloppy
          .start pipe { dbGame =>
          // apply the result from the board or the tags
          game.situation.status match {
            case Some(situationStatus) => dbGame.finish(situationStatus, game.situation.winner).game
            case None =>
              parsed.tags.resultColor
                .map {
                  case Some(color)                            => TagResult(status, color.some)
                  case None if status == Status.UnknownFinish => TagResult(Status.Draw, none)
                  case None                                   => TagResult(status, none)
                }
                .filter(_.status > Status.Started)
                .fold(dbGame) { res =>
                  dbGame.finish(res.status, res.winner).game
                }
          }
        }

        Preprocessed(NewGame(dbGame), replay.copy(state = game), initialFen, parsed)
      }
    }
}