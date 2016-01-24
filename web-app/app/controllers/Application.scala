package controllers


import jp.t2v.lab.play2.auth.{Logout, OptionalAuthElement}
import org.joda.time.{DateTime, LocalDate}
import org.slf4s
import play.Logger
import play.api.mvc._
import velocorner.model.{Activity, Progress, YearlyProgress}
import velocorner.proxy.StravaFeed
import velocorner.util.Metrics

import scala.annotation.tailrec
import scala.concurrent.Future

object Application extends Controller with OptionalAuthElement with AuthConfigSupport with Logout with Metrics {

  import scala.concurrent.ExecutionContext.Implicits.global

  override val log = new slf4s.Logger(Logger.underlying())

  def index = StackAction{ implicit request =>
    Logger.info("rendering landing page...")

    val context = timed("building page context") {
      val maybeAccount = loggedIn
      Logger.info(s"rendering for $maybeAccount")
      val storage = Global.getStorage
      val currentYear = LocalDate.now().getYear

      val yearlyProgress = maybeAccount.map(account => YearlyProgress.from(storage.dailyProgress(account.athleteId))).getOrElse(Iterable.empty)
      val flattenedYearlyProgress = YearlyProgress.zeroOnMissingDate(yearlyProgress)
      val aggregatedYearlyProgress = YearlyProgress.aggregate(yearlyProgress)
      val currentYearStatistics = aggregatedYearlyProgress.find(_.year == currentYear).map(_.progress.last.progress).getOrElse(Progress.zero)

      LandingPageContext(
        maybeAccount,
        currentYearStatistics,
        flattenedYearlyProgress,
        aggregatedYearlyProgress
      )
    }

    Ok(views.html.index(context))
  }

  def refresh = AsyncStack{ implicit request =>
    val maybeAccount = loggedIn
    Logger.info(s"refreshing for $maybeAccount")
    maybeAccount.foreach{ account =>
      // allow refresh after some time only
      val now = DateTime.now()
      val lastUpdate = account.lastUpdate.getOrElse(now.minusYears(1)) // if not set, then consider it as very old
      val diffInMillis = now.getMillis - lastUpdate.getMillis
      if (diffInMillis > 60000) {
        log.info(s"last update was $diffInMillis millis ago...")
        val storage = Global.getStorage
        val feed = Global.getFeed

        val lastActivitiyIds = storage.listRecentActivities(account.athleteId, StravaFeed.maxItemsPerPage).map(_.id).toSet

        @tailrec
        def list(page: Int, accu: Iterable[Activity]): Iterable[Activity] = {
          val activities = feed.listAthleteActivities(page, StravaFeed.maxItemsPerPage)
          val activityIds = activities.map(_.id).toSet
          if (activities.size < StravaFeed.maxItemsPerPage || activityIds.intersect(lastActivitiyIds).nonEmpty) activities.filter(a => !lastActivitiyIds.contains(a.id)) ++ accu
          else list(page + 1, activities ++ accu)
        }
        val newActivities = list(1, List.empty)
        log.info(s"found ${newActivities.size} new activities")
        storage.store(newActivities)
        storage.store(account.copy(lastUpdate = Some(now)))
      }
    }
    Future.successful(Redirect(routes.Application.index()))
  }

  def logout = Action.async{ implicit request =>
    gotoLogoutSucceeded
  }

  def about = Action {
    Ok(views.html.about())
  }
}