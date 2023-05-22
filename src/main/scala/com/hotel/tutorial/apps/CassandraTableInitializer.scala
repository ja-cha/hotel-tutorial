package com.hotel.tutorial.apps

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.stream.scaladsl.{Sink, Source}

import java.time.LocalDate
import scala.concurrent.Future

object CassandraTableInitializer {

  implicit val system = ActorSystem(Behaviors.empty, "CassandraSystem")

  import system.executionContext
  val session = CassandraSessionRegistry.get(system).sessionFor("akka.projection.cassandra.session-config")

  def initializeTables(): Unit = {

    val availabilities = Source(
      for {
        hotelId <- (1 to 5).map(i => s"hotel_$i")
        roomNumber <- 1 to 10 // 10 rooms/hotel
        date <- (0 to 365).map(LocalDate.of(2023, 1, 1).plusDays(_))
      } yield (hotelId, roomNumber, date)
    )

    // make rooms available for each room on every date for the year
    val availableRooms = availabilities.mapAsync((8)) {
      case (hotelId, roomNumber, date) =>
        //write  entry to cassandra
        session.executeWrite(
          s"""INSERT INTO hotel.available_rooms_by_hotel_date(hotel_id, date, room_number, is_available)
             |VALUES ('$hotelId', '$date', $roomNumber, true)""".stripMargin
        )
    }
      .runWith(Sink.last)
      .recover(e => println(s"making rooms available failed: $e"))

    Future.sequence(
      availableRooms :: List(
        // "truncate hotel.available_rooms_by_hotel_date",
        "truncate akka.all_persistence_ids",
        "truncate akka.messages",
        "truncate akka.metadata",
        "truncate akka.tag_scanning",
        "truncate akka.tag_views",
        "truncate akka.tag_write_progress",
        "truncate akka_snapshot.snapshots",
        "truncate reservation.reservations_by_hotel_date",
        "truncate reservation.reservations_by_guest",
      ).map(session.executeWrite(_))
    )
      .recover(e => println(s"failed to initialize tables, reason: $e"))
      .onComplete { _ =>
        println("all tables have been initialized.")
        system.terminate
      }
  }

  def main(args: Array[String]): Unit = {
    initializeTables()
  }
}
