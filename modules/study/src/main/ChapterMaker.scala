package lila.study

import chess.format.pgn.Tag
import chess.format.{ Forsyth, FEN }
import chess.variant.{ Variant, Crazyhouse }
import lila.game.{ Game, Pov, GameRepo, Namer }
import lila.importer.{ Importer, ImportData }
import lila.user.User

private final class ChapterMaker(
    domain: String,
    lightUser: lila.common.LightUser.Getter,
    chat: akka.actor.ActorSelection,
    importer: Importer,
    pgnFetch: PgnFetch) {

  import ChapterMaker._

  def apply(study: Study, data: Data, order: Int, userId: User.ID): Fu[Option[Chapter]] = {
    data.game.??(parsePov) flatMap {
      case None =>
        data.game.??(pgnFetch.fromUrl) map {
          case Some(pgn) => fromFenOrPgnOrBlank(study, data.copy(pgn = pgn.some), order, userId).some
          case None      => fromFenOrPgnOrBlank(study, data, order, userId).some
        }
      case Some(pov) => fromPov(study, pov, data, order, userId)
    }
  }

  def fromFenOrPgnOrBlank(study: Study, data: Data, order: Int, userId: User.ID): Chapter =
    data.pgn.filter(_.trim.nonEmpty) match {
      case Some(pgn) => fromPgn(study, pgn, data, order, userId)
      case None      => fromFenOrBlank(study, data, order, userId)
    }

  private def fromPgn(study: Study, pgn: String, data: Data, order: Int, userId: User.ID): Chapter =
    PgnImport(pgn).toOption.fold(fromFenOrBlank(study, data, order, userId)) { res =>
      Chapter.make(
        studyId = study.id,
        name = (for {
        white <- Tag.find(res.tags, "White")
        black <- Tag.find(res.tags, "Black")
        if Chapter isDefaultName data.name
      } yield s"$white vs $black") | data.name,
        setup = Chapter.Setup(
          none,
          res.variant,
          data.realOrientation),
        root = res.root,
        tags = res.tags,
        order = order,
        ownerId = userId,
        conceal = data.conceal option Chapter.Ply(res.root.ply))
    }

  private def fromFenOrBlank(study: Study, data: Data, order: Int, userId: User.ID): Chapter = {
    val variant = data.variant.flatMap(Variant.apply) | Variant.default
    (data.fen.map(_.trim).filter(_.nonEmpty).flatMap { fenStr =>
      Forsyth.<<<@(variant, fenStr)
    } match {
      case Some(sit) => Node.Root(
        ply = sit.turns,
        fen = FEN(Forsyth.>>(sit)),
        check = sit.situation.check,
        crazyData = sit.situation.board.crazyData,
        children = Node.emptyChildren) -> true
      case None => Node.Root(
        ply = 0,
        fen = FEN(variant.initialFen),
        check = false,
        crazyData = variant.crazyhouse option Crazyhouse.Data.init,
        children = Node.emptyChildren) -> false
    }) match {
      case (root, isFromFen) => Chapter.make(
        studyId = study.id,
        name = data.name,
        setup = Chapter.Setup(
          none,
          variant,
          data.realOrientation,
          fromFen = isFromFen option true),
        root = root,
        tags = Nil,
        order = order,
        ownerId = userId,
        conceal = None)
    }
  }

  private def fromPov(study: Study, pov: Pov, data: Data, order: Int, userId: User.ID, initialFen: Option[FEN] = None): Fu[Option[Chapter]] =
    game2root(pov.game, initialFen) map { root =>
      Chapter.make(
        studyId = study.id,
        name =
        if (Chapter isDefaultName data.name)
          Namer.gameVsText(pov.game, withRatings = false)(lightUser)
        else data.name,
        setup = Chapter.Setup(
          !pov.game.synthetic option pov.game.id,
          pov.game.variant,
          data.realOrientation),
        root = root,
        tags = Nil,
        order = order,
        ownerId = userId,
        conceal = data.conceal option Chapter.Ply(root.ply)).some
    } addEffect { _ =>
      notifyChat(study, pov.game, userId)
    }

  def notifyChat(study: Study, game: Game, userId: User.ID) =
    if (study.isPublic) List(game.id, s"${game.id}/w") foreach { chatId =>
      chat ! lila.chat.actorApi.UserTalk(
        chatId = chatId,
        userId = userId,
        text = s"I'm studying this game on lichess.org/study/${study.id}")
    }

  def game2root(game: Game, initialFen: Option[FEN] = None): Fu[Node.Root] =
    initialFen.fold(GameRepo initialFen game) { fen =>
      fuccess(fen.value.some)
    } map { initialFen =>
      Node.Root.fromRoot {
        lila.round.TreeBuilder(
          game = game,
          analysis = none,
          initialFen = initialFen | game.variant.initialFen,
          withOpening = false)
      }
    }

  private val UrlRegex = {
    val escapedDomain = domain.replace(".", "\\.")
    s""".*$escapedDomain/(\\w{8,12}).*"""
  }.r

  private def parsePov(str: String): Fu[Option[Pov]] = str match {
    case s if s.size == Game.gameIdSize => GameRepo.pov(s, chess.White)
    case s if s.size == Game.fullIdSize => GameRepo.pov(s)
    case UrlRegex(id)                   => parsePov(id)
    case _                              => fuccess(none)
  }
}

private[study] object ChapterMaker {

  case class Data(
      name: String,
      game: Option[String],
      variant: Option[String],
      fen: Option[String],
      pgn: Option[String],
      orientation: String,
      conceal: Boolean,
      initial: Boolean) {

    def realOrientation = chess.Color(orientation) | chess.White
  }

  case class EditData(
      id: String,
      name: String,
      orientation: String,
      conceal: Boolean) {

    def realOrientation = chess.Color(orientation) | chess.White
  }
}
