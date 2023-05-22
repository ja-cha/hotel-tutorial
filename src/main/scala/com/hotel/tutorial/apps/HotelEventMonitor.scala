package com.hotel.tutorial.apps

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.stream.scaladsl.{Sink, Source}
import CassandraTableInitializer.system
import com.hotel.tutorial.model.{Reservation, ReservationAccepted, ReservationCancelled, ReservationChanged}

import java.time.temporal.ChronoUnit
import scala.concurrent.Future

// Note: Independent Event Monitor, tasked with reading events that have been persisted in Cassandra.
// Akka a persistence Query
// Monitor events associated with persistence id, which is how a hotel is uniquely identified
// This monitor represents the READ side in the Query portion of CQRS, as opposed to the write side.
// Even though responsible for reading data, this monitor may still write to the db, but doing so would only be for its own internal purposes,
// such as executing tasks particularly related to its own read model.
// This application may be dedicated only to this particular hotel.

object HotelEventMonitor {

  import system.executionContext

  // set up the actor system
  implicit val system: ActorSystem[_] = ActorSystem(Behaviors.empty, "HotelEventMonitorSystem")

  // utility that will monitor all events in the persistence store associated with a particular persistence id (for each store)
  val readJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  // Cassandra session
  val session = CassandraSessionRegistry.get(system).sessionFor("akka.projection.cassandra.session-config")

  // with this read journal we can fetch all the persistence ids and events for a particular journal, Cassandra in this case,
  // since persistence ids can come and go over time as actors update in the db, the persistenceIds attribute is typed
  // as Source, from Akka Streams. It will allow you to deal with an infinite amount of data from a variety of sources.

  //calling persistenceIds will return the hotels in this case
  val persistenceIds: Source[String, NotUsed] = readJournal.persistenceIds()
  // in order to consume this source, it needs to connect to a sink
  val consumptionSink = Sink.foreach(println)
  //now we have static components.
  //to make an actionable component out of the two, we have to connect them, resulting in a graph.
  val connectedGraph = persistenceIds.to(consumptionSink)

  //In order to tap into an actively flowing data stream,
  //we have to invoke the run() method on the graph
  //EXAMPLE: connectedGraph.run()
  //at time of its creation the stream will materialize all the appropriate resources, sockets and so forth under the hood

  // we can also ask the read journal to retrieve all events associated with a particular persistence id.
  // in doing so we will print every event in order of occurrence.
  //readJournal.eventsByPersistenceId(...) will return a Source which wraps an EventEnvelope, which we will have to unwrap to extract the events
  val allEventsForTestHotel =
          readJournal.eventsByPersistenceId("hotel_1", 0, Long.MaxValue)
            .map(envelope => (envelope.event, envelope.sequenceNr))
            .mapAsync(8) {
              case (ReservationAccepted(reservation), sequenceNumber) =>
                println(s"($sequenceNumber) RESERVATION ACCEPTED: $reservation")
                makeReservation(reservation)
              case (ReservationChanged(oldReservation, newReservation), sequenceNumber) =>
                println(s"($sequenceNumber) RESERVATION CHANGED: from $oldReservation to $newReservation")
                for {
                  _ <- removeReservation(oldReservation)
                  _ <- makeReservation(newReservation)
                }yield ()
              case (ReservationCancelled(reservation), sequenceNumber) =>
                println(s"($sequenceNumber) RESERVATION CANCELLED: $reservation")
               removeReservation(reservation)
            }

  // all events for a persistence id
  def main(args: Array[String]): Unit = {
    allEventsForTestHotel.to(Sink.ignore).run()
  }


  def makeReservation(reservation: Reservation): Future[Unit] = {
    val Reservation(guestUUID, hotelId, startDate, endDate, roomNumber, confirmationCode) = reservation
    val startLocalDate = startDate.toLocalDate
    val endLocalDate = endDate.toLocalDate
    val daysBlocked = startLocalDate.until(endLocalDate, ChronoUnit.DAYS).toInt

    val blockedDates = for {
      days <- 0 until daysBlocked
    } yield session.executeWrite(
      s"""UPDATE
         |  hotel.available_rooms_by_hotel_date SET is_available = false
         |WHERE
         |  hotel_id = '$hotelId' AND
         |  date='${startLocalDate.plusDays(days)}' AND
         |  room_number=$roomNumber""".stripMargin
    ).recover(e => println(s"blocking for date failed, reason: ${e}"))

    val reservationsByDate = session.executeWrite(
      s"""INSERT
         |  INTO reservation.reservations_by_hotel_date (hotel_id, start_date, end_date, room_number, confirm_number, guest_id)
         |  VALUES ('$hotelId', '$startDate', '$endDate', $roomNumber, '$confirmationCode', $guestUUID)""".stripMargin
    ).recover(e => println(s"reservation for date failed, reason: ${e}"))

    val reservationsByGuest = session.executeWrite(
      s"""INSERT
         |  INTO reservation.reservations_by_guest (guest_last_name, hotel_id, start_date, end_date, room_number, confirm_number, guest_id)
         |  VALUES ('Abt', '$hotelId', '$startDate', '$endDate', $roomNumber, '$confirmationCode', $guestUUID)""".stripMargin
    ).recover(e => println(s"reservation for guest failed, reason: ${e}"))

    Future.sequence(reservationsByDate :: reservationsByGuest :: blockedDates.toList).map(_ => ())
  }

  def removeReservation(reservation: Reservation): Future[Unit] = {
    val Reservation(guestUUID, hotelId, startDate, endDate, roomNumber, confirmationCode) = reservation
    val startLocalDate = startDate.toLocalDate
    val endLocalDate = endDate.toLocalDate
    val daysBlocked = startLocalDate.until(endLocalDate, ChronoUnit.DAYS).toInt

    val blockedDates = for {
      days <- 0 until daysBlocked
    }  yield session.executeWrite(
      s"""UPDATE
         |  hotel.available_rooms_by_hotel_date SET is_available = true
         |WHERE
         |  hotel_id = '$hotelId' AND
         |  date='${startLocalDate.plusDays(days)}' AND
         |  room_number=$roomNumber""".stripMargin
    ).recover(e => println(s"blocking for date failed, reason: ${e}"))


    val reservationsByDate = session.executeWrite(
      s"""DELETE
         |FROM
         |  reservation.reservations_by_hotel_date
         |WHERE
         |  hotel_id = '$hotelId' AND
         |  start_date = '$startDate' AND
         |  room_number = $roomNumber""".stripMargin
    ).recover(e => println(s"reservation for date failed, reason: ${e}"))

    val reservationsByGuest = session.executeWrite(
      s"""DELETE
         |FROM
         |  reservation.reservations_by_guest
         |WHERE
         |  guest_last_name = 'Abt' AND
         |  confirm_number = '$confirmationCode'""".stripMargin
    ).recover(e => println(s"reservation for guest failed, reason: ${e}"))

    Future.sequence(reservationsByDate :: reservationsByGuest :: blockedDates.toList).map(_ => ())
  }

}
