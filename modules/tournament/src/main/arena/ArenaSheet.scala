package lila.tournament
package arena

import org.joda.time.DateTime
import lila.user.User

// most recent first
case class Sheet(scores: List[Sheet.Score]) {
  val total  = scores.foldLeft(0)(_ + _.value)
  def onFire = Sheet.isOnFire(scores)
}

object Sheet {
  case class Version private (id: Int) extends AnyVal
  object Version {
    val V1 = Version(1)
    val V2 = Version(2)

    private val v2date = new DateTime(2020, 4, 21, 0, 0, 0)

    def of(date: DateTime) = if (date isBefore v2date) V1 else V2
  }

  case class Streakable(value: Boolean) extends AnyVal

  case class Flag(value: Int) extends AnyVal
  object Flag {
    val Null          = Flag(0)
    val Normal        = Flag(1)
    val StreakStarter = Flag(2)
    val Double        = Flag(3)
  }

  case class Berserk private (encoded: Int) extends AnyVal
  object Berserk {
    val No      = Berserk(0 << 2)
    val Valid   = Berserk(1 << 2)
    val Invalid = Berserk(2 << 2)
  }

  case class Result private (encoded: Int) extends AnyVal
  object Result {
    val Win  = Result(0 << 4)
    val Draw = Result(1 << 4)
    val Loss = Result(2 << 4)
    val DQ   = Result(3 << 4)
  }

  case class Score private (encoded: Int) extends AnyVal {
    // flag:    2 bits
    // berserk: 2 bits
    // result:  2 bits
    @inline
    def flag = Flag(encoded & 0x3) // value is public
    @inline
    def berserk = Berserk(encoded & (0x3 << 2))
    @inline
    def res = Result(encoded & (0x3 << 4))

    def isBerserk = berserk != Berserk.No

    def isWin =
      res match {
        case Result.Win  => Some(true)
        case Result.Loss => Some(false)
        case _           => None
      }

    def isDraw = res == Result.Draw

    def value = ((res, flag) match {
      case (Result.Win, Flag.Double)  => 4
      case (Result.Win, _)            => 2
      case (Result.Draw, Flag.Double) => 2
      case (Result.Draw, Flag.Null)   => 0
      case (Result.Draw, _)           => 1
      case _                          => 0
    }) + {
      if (res == Result.Win && berserk == Berserk.Valid) 1 else 0
    }

    def withFlag(newFlag: Flag) = Score(res, newFlag, berserk)
  }
  object Score {
    def apply(res: Result, flag: Flag, berserk: Berserk): Score = Score(
      flag.value | berserk.encoded | res.encoded
    )
  }

  val emptySheet = Sheet(Nil)

  def buildFromScratch(
      userId: User.ID,
      pairings: List[Pairing],
      version: Version,
      streakable: Streakable
  ): Sheet =
    Sheet {
      val nexts = (pairings drop 1 map some) :+ None
      pairings.zip(nexts).foldLeft(List.empty[Score]) { case (scores, (p, n)) =>
        val berserk = if (p berserkOf userId) {
          if (p.notSoQuickFinish) Berserk.Valid else Berserk.Invalid
        } else Berserk.No
        (p.winner match {
          case None if p.quickDraw => Score(Result.DQ, Flag.Normal, berserk)
          case None =>
            Score(
              Result.Draw,
              if (streakable.value && isOnFire(scores)) Flag.Double
              else if (version != Version.V1 && !p.longGame && isDrawStreak(scores)) Flag.Null
              else Flag.Normal,
              berserk
            )
          case Some(w) if userId == w =>
            Score(
              Result.Win,
              if (!streakable.value) Flag.Normal
              else if (isOnFire(scores)) Flag.Double
              else if (scores.headOption.exists(_.flag == Flag.StreakStarter)) Flag.StreakStarter
              else
                n match {
                  case None                       => Flag.StreakStarter
                  case Some(s) if s.wonBy(userId) => Flag.StreakStarter
                  case _                          => Flag.Normal
                },
              berserk
            )
          case _ => Score(Result.Loss, Flag.Normal, berserk)
        }) :: scores
      }
    }

  def addResult(sheet: Sheet, userId: User.ID, p: Pairing, streakable: Streakable): Sheet =
    Sheet {
      val scores = sheet.scores
      val berserk = if (p berserkOf userId) {
        if (p.notSoQuickFinish) Berserk.Valid else Berserk.Invalid
      } else Berserk.No
      val score = p.winner match {
        case None if p.quickDraw => Score(Result.DQ, Flag.Normal, berserk)
        case None =>
          Score(
            Result.Draw,
            if (streakable.value && isOnFire(scores)) Flag.Double
            else if (!p.longGame && isDrawStreak(scores)) Flag.Null
            else Flag.Normal,
            berserk
          )
        case Some(w) if userId == w =>
          Score(
            Result.Win,
            if (!streakable.value) Flag.Normal
            else if (isOnFire(scores)) Flag.Double
            else Flag.StreakStarter,
            berserk
          )
        case _ => Score(Result.Loss, Flag.Normal, berserk)
      }
      // update the streak flag of the previous score
      val prevScores = scores.headOption
        .filter(_.flag == Flag.StreakStarter && !p.wonBy(userId))
        .fold(scores)(_.withFlag(Flag.Normal) :: scores.tail)

      score :: prevScores
    }

  private def isOnFire(scores: List[Score]) =
    scores.headOption.exists(_.res == Result.Win) &&
      scores.lift(1).exists(_.res == Result.Win)

  @scala.annotation.tailrec
  private def isDrawStreak(scores: List[Score]): Boolean =
    scores match {
      case Nil => false
      case s :: more =>
        s.isWin match {
          case None        => true
          case Some(true)  => false
          case Some(false) => isDrawStreak(more)
        }
    }
}
