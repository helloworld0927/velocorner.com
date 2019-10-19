package velocorner.manual.storage

import com.typesafe.scalalogging.LazyLogging
import velocorner.manual.{AggregateActivities, AwaitSupport, MyMacConfig}
import velocorner.storage.Storage
import velocorner.util.Metrics


object AggregateAthleteActivitiesFromStorageApp extends App with Metrics with LazyLogging with AggregateActivities with AwaitSupport with MyMacConfig {

  val storage = Storage.create("or")
  storage.initialize()
  val progress = timed("aggregation")(await(storage.dailyProgressForAthlete(432909, "Ride")))
  printAllProgress(progress)

  logger.info("done...")
  storage.destroy()
}
