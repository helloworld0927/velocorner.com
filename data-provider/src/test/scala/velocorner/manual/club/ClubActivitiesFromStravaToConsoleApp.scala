package velocorner.manual.club

import org.slf4s.Logging
import velocorner.SecretConfig
import velocorner.manual.{AwaitSupport, MyMacConfig}
import velocorner.feed.{HttpFeed, StravaActivityFeed}
import velocorner.model.strava.Club
import velocorner.util.CloseableResource

object ClubActivitiesFromStravaToConsoleApp extends App with Logging with CloseableResource with AwaitSupport with MyMacConfig {

  withCloseable(new StravaActivityFeed(None, SecretConfig.load())) { feed =>
    val activities = await(feed.listRecentClubActivities(Club.Velocorner))
    activities.foreach { a =>
      log.info(s"[${a.start_date_local}] ${a.athlete} -> ${a.distance / 1000} km")
    }
    log.info(s"got ${activities.size} club activities")
  }
  HttpFeed.shutdown()
}
