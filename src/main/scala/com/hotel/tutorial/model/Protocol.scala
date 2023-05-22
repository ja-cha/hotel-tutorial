package com.hotel.tutorial.model

import akka.actor.typed.ActorRef

import java.sql.Date

// commands the hotel actor wil receive from booking service
sealed trait Command
case class RequestReservation(guestUUID: String, startDate: Date, endDate: Date, roomNumber: Int, replyTo: ActorRef[HotelProtocol]) extends Command
case class ChangeReservation(confirmationCode: String, startDate:Date, endDate:Date, roomNumber: Int, replyTo: ActorRef[HotelProtocol]) extends Command
case class CancelReservation(confirmationCode: String, replyTo:ActorRef[HotelProtocol]) extends Command


//events persisted into cassandra
sealed trait Event
case class ReservationAccepted(reservation: Reservation) extends Event with HotelProtocol
case class ReservationChanged(originalReservation: Reservation, updatedReservation: Reservation) extends Event with HotelProtocol
case class ReservationCancelled(reservation: Reservation) extends Event with HotelProtocol

// communication with the manager
case class CommandFailure( reason: String) extends HotelProtocol

//the protocol that the reservation agent will handle
trait HotelProtocol
case class ManageHotel(hotel: ActorRef[Command]) extends HotelProtocol
case class Generate(nCommands: Int) extends HotelProtocol