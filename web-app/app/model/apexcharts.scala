package model

import velocorner.api.heatmap.{HeatmapPoint, HeatmapSeries}
import velocorner.api.strava.Activity

object apexcharts {

  def toDistanceHeatmap(items: Iterable[Activity], activityType: String): List[HeatmapSeries] = {
    val ranges = activityType match {
      case "Ride" => List(
        HeatmapPoint("10km", 10),
        HeatmapPoint("50km", 50),
        HeatmapPoint("100km", 100),
        HeatmapPoint("150km", 150),
        HeatmapPoint("200km", 200),
        HeatmapPoint("250km", 250)
      )
      case _ => List(
        HeatmapPoint("3km", 3),
        HeatmapPoint("5km", 5),
        HeatmapPoint("10km", 10),
        HeatmapPoint("15km", 15),
        HeatmapPoint("20km", 20)
      )
    }
    toYearlyHeatmap(items, _.distance.toLong / 1000, ranges)
  }

  def toElevationHeatmap(items: Iterable[Activity]): List[HeatmapSeries] =
    toYearlyHeatmap(items, _.total_elevation_gain.toLong, List(
      HeatmapPoint("300m", 300),
      HeatmapPoint("600m", 600),
      HeatmapPoint("1000m", 1000),
      HeatmapPoint("1500m", 1500),
      HeatmapPoint("2000m", 2000),
      HeatmapPoint("3000m", 3000)
    ))

  // must return a list because of the swagger spec generator
  private[model] def toYearlyHeatmap(items: Iterable[Activity], fun: Activity => Long, ranges: List[HeatmapPoint]): List[HeatmapSeries] = {
    val year2Values = items.groupMap(_.getStartDateLocal().year().get())(fun)
    toYearlyHeatmap(year2Values, ranges)
  }

  // returns with a sorted series, sorted by year and ranges
  private[model] def toYearlyHeatmap(year2Values: Map[Int, Iterable[Long]], ranges: List[HeatmapPoint]): List[HeatmapSeries] =
    year2Values.map{ case (year, sample) =>
      val biggest = ranges.last
      val name2Count = sample
        .map(point => ranges.find(_.y > point).getOrElse(biggest).copy(y = point))
        .groupBy(_.x)
        .view.mapValues(_.size)
      // collect in the order given in the ranges and fill missing buckets with zero
      val heatmapPoints = ranges
        .foldRight(List.empty[HeatmapPoint])(
          (ref: HeatmapPoint, accu: List[HeatmapPoint]) =>
            accu :+ name2Count
              .get(ref.x)
              .map(HeatmapPoint(ref.x, _))
              .getOrElse(HeatmapPoint(ref.x, 0))
        )
        .reverse
      HeatmapSeries(year.toString, heatmapPoints)
    }.toList.sortBy(_.name).reverse
}
