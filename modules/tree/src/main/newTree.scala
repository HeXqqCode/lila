package lila.tree

import alleycats.Zero
import monocle.syntax.all.*
import chess.Centis
import chess.format.pgn.{ Node as ChessNode }
import chess.format.pgn.Node.*
import chess.format.pgn.{ Glyph, Glyphs }
import chess.format.{ Fen, Uci, UciCharPair, UciPath }
import chess.opening.Opening
import chess.{ Ply, Square, Check }
import chess.variant.{ Variant, Crazyhouse }
import play.api.libs.json.*
import ornicar.scalalib.ThreadLocalRandom

import lila.common.Json.{ *, given }

import Node.{ Comments, Comment, Gamebook, Shapes }

case class Metas(
    ply: Ply,
    fen: Fen.Epd,
    check: Check,
    // None when not computed yet
    dests: Option[Map[Square, List[Square]]] = None,
    drops: Option[List[Square]] = None,
    eval: Option[Eval] = None,
    shapes: Node.Shapes,
    comments: Node.Comments,
    gamebook: Option[Node.Gamebook] = None,
    glyphs: Glyphs,
    opening: Option[Opening] = None,
    clock: Option[Centis],
    crazyData: Option[Crazyhouse.Data]
    // TODO, add support for variationComments
)

case class NewBranch(
    id: UciCharPair,
    // additional data to make searching with path easier
    path: UciPath,
    move: Uci.WithSan,
    comp: Boolean = false, // generated by a computer analysis
    forceVariation: Boolean,
    metas: Metas
):
  override def toString                    = s"$id, ${move.uci}"
  def withClock(centis: Option[Centis])    = this.focus(_.metas.clock).set(centis)
  def withForceVariation(force: Boolean)   = copy(forceVariation = force)
  def isCommented                          = metas.comments.value.nonEmpty
  def setComment(comment: Comment)         = this.focus(_.metas.comments).modify(_.set(comment))
  def deleteComment(commentId: Comment.Id) = this.focus(_.metas.comments).modify(_.delete(commentId))
  def deleteComments                       = this.focus(_.metas.comments).set(Comments.empty)
  def setGamebook(gamebook: Gamebook)      = this.focus(_.metas.gamebook).set(gamebook.some)
  def setShapes(s: Shapes)                 = this.focus(_.metas.shapes).set(s)
  def toggleGlyph(glyph: Glyph)            = this.focus(_.metas.glyphs).modify(_ toggle glyph)
  def clearAnnotations = this.focus(_.metas).modify(_.copy(shapes = Shapes.empty, glyphs = Glyphs.empty))
  def setComp          = copy(comp = true)
  def merge(n: NewBranch): NewBranch =
    copy(
      metas = metas.copy(
        shapes = metas.shapes ++ n.metas.shapes,
        comments = metas.comments ++ n.metas.comments,
        gamebook = n.metas.gamebook orElse metas.gamebook,
        glyphs = metas.glyphs merge n.metas.glyphs,
        eval = n.metas.eval orElse metas.eval,
        clock = n.metas.clock orElse metas.clock,
        crazyData = n.metas.crazyData orElse metas.crazyData
      ),
      forceVariation = n.forceVariation || forceVariation
    )

type NewTree = ChessNode[NewBranch]

object NewTree:
  // default case class constructor not working with type alias?
  def apply(value: NewBranch, child: Option[NewTree], variation: Option[NewTree]) =
    ChessNode(value, child, variation)

  extension [A](xs: List[ChessNode[A]])
    def toVariations: Option[ChessNode[A]] =
      xs.reverse.foldLeft(none[ChessNode[A]])((acc, x) => x.copy(variation = acc).some)

    def toChild: Option[ChessNode[A]] =
      xs.reverse.foldLeft(none[ChessNode[A]])((acc, x) => x.copy(child = acc).some)

  extension [A](xs: List[A])
    def toVariations[B](f: A => ChessNode[B]) =
      xs.reverse.foldLeft(none[ChessNode[B]])((acc, x) => f(x).copy(variation = acc).some)

    def toChild[B](f: A => ChessNode[B]) =
      xs.reverse.foldLeft(none[ChessNode[B]])((acc, x) => f(x).copy(child = acc).some)

  // Optional for the first node with the given id
  def filterById(id: UciCharPair) = ChessNode.filterOptional[NewBranch](_.id == id)

  extension (newTree: NewTree)
    def dropFirstChild                  = newTree.copy(child = None)
    def color                           = newTree.value.metas.ply.color
    def mainlineNodeList: List[NewTree] = newTree.dropFirstChild :: newTree.child.??(_.mainlineNodeList)

    def getChild(id: UciCharPair): Option[NewTree] = newTree.childAndVariations.find(_.value.id == id)

    def hasChild(id: UciCharPair): Boolean = newTree.childAndVariations.exists(_.value.id == id)

    def variationsWithIndex: List[(NewTree, Int)] =
      newTree.variation.fold(List.empty[(NewTree, Int)])(_.variations.zipWithIndex)

    def nodeAt(path: UciPath): Option[NewTree] =
      path.split.flatMap: (head, rest) =>
        newTree.child.flatMap(_.nodeAt(rest)) orElse
          newTree.variation.flatMap(_.nodeAt(rest)) orElse:
          head == newTree.value.id option newTree

    // TODO: merge two nodes if they have the same id
    def addVariation(variation: NewTree): NewTree =
      newTree.copy(variation = newTree.variation.mergeVariations(variation.some))

    def clearVariations: NewTree =
      newTree.copy(variation = None)

    def merge(n: NewTree): NewTree =
      newTree.copy(
        value = newTree.value.merge(n.value),
        child = newTree.child orElse n.child, // TODO: verify logic
        variation = newTree.variation.mergeVariations(n.variation)
      )

case class NewRoot(metas: Metas, tree: Option[NewTree]):
  override def toString = s"$tree"
