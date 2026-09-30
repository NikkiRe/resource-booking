package dev.nikita.booking.grpc

import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import dev.nikita.booking.ApiException
import dev.nikita.booking.Booking
import dev.nikita.booking.BookingService
import dev.nikita.booking.Resource
import dev.nikita.booking.ResourceRepository
import dev.nikita.booking.invalidRequest
import dev.nikita.booking.notFound
import dev.nikita.booking.proto.BookingApiGrpc
import dev.nikita.booking.proto.CancelBookingRequest
import dev.nikita.booking.proto.CreateBookingResponse
import dev.nikita.booking.proto.GetAvailabilityRequest
import dev.nikita.booking.proto.GetBookingRequest
import dev.nikita.booking.proto.GetResourceRequest
import dev.nikita.booking.proto.ListResourcesResponse
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import dev.nikita.booking.CreateBookingRequest as BookingCommand
import dev.nikita.booking.proto.Availability as GrpcAvailability
import dev.nikita.booking.proto.Booking as GrpcBooking
import dev.nikita.booking.proto.BookingStatus as GrpcBookingStatus
import dev.nikita.booking.proto.CreateBookingRequest as GrpcCreateBookingRequest
import dev.nikita.booking.proto.Resource as GrpcResource
import dev.nikita.booking.proto.TimeWindow as GrpcTimeWindow

@Component
class BookingGrpcService(private val bookings: BookingService, private val resources: ResourceRepository) : BookingApiGrpc.BookingApiImplBase() {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun listResources(request: Empty, observer: StreamObserver<ListResourcesResponse>) = respond(observer) {
        ListResourcesResponse.newBuilder().addAllResources(resources.list().map { it.toProto() }).build()
    }

    override fun getResource(request: GetResourceRequest, observer: StreamObserver<GrpcResource>) = respond(observer) {
        (resources.find(uuid(request.id, "id")) ?: notFound("Resource")).toProto()
    }

    override fun getAvailability(request: GetAvailabilityRequest, observer: StreamObserver<GrpcAvailability>) = respond(observer) {
        val availability = bookings.availability(
            uuid(request.resourceId, "resourceId"),
            instant(request.from, request.hasFrom(), "from"),
            instant(request.to, request.hasTo(), "to")
        )
        GrpcAvailability.newBuilder()
            .setResourceId(availability.resourceId.toString())
            .setFrom(availability.from.toProto())
            .setTo(availability.to.toProto())
            .addAllFree(availability.free.map {
                GrpcTimeWindow.newBuilder().setStartsAt(it.startsAt.toProto()).setEndsAt(it.endsAt.toProto()).build()
            }).build()
    }

    override fun createBooking(request: GrpcCreateBookingRequest, observer: StreamObserver<CreateBookingResponse>) = respond(observer) {
        val key = IdempotencyKeyInterceptor.KEY.get() ?: invalidRequest("idempotency-key metadata is required")
        val command = BookingCommand(
            uuid(request.resourceId, "resourceId"), request.bookedBy,
            instant(request.startsAt, request.hasStartsAt(), "startsAt"),
            instant(request.endsAt, request.hasEndsAt(), "endsAt")
        )
        val result = bookings.create(key, command)
        CreateBookingResponse.newBuilder().setBooking(result.booking.toProto()).setReplayed(result.replayed).build()
    }

    override fun getBooking(request: GetBookingRequest, observer: StreamObserver<GrpcBooking>) = respond(observer) {
        bookings.get(uuid(request.id, "id")).toProto()
    }

    override fun cancelBooking(request: CancelBookingRequest, observer: StreamObserver<GrpcBooking>) = respond(observer) {
        bookings.cancel(uuid(request.id, "id")).toProto()
    }

    private fun <T> respond(observer: StreamObserver<T>, action: () -> T) {
        val context = Context.current()
        if (context.isCancelled) {
            observer.onError((Contexts.statusFromCancelled(context) ?: Status.CANCELLED).asRuntimeException())
            return
        }
        try {
            val response = action()
            if (!context.isCancelled) {
                observer.onNext(response)
                observer.onCompleted()
            }
        } catch (exception: ApiException) {
            val status = when (exception.status) {
                400 -> Status.INVALID_ARGUMENT
                404 -> Status.NOT_FOUND
                409 -> Status.ALREADY_EXISTS
                else -> Status.INTERNAL
            }
            val trailers = Metadata().apply { put(ERROR_CODE, exception.code) }
            observer.onError(status.withDescription(exception.message).asRuntimeException(trailers))
        } catch (exception: DataAccessResourceFailureException) {
            logger.error("gRPC database access failed", exception)
            observer.onError(Status.UNAVAILABLE.withDescription("Database is unavailable").asRuntimeException())
        } catch (exception: StatusRuntimeException) {
            observer.onError(exception)
        } catch (exception: Exception) {
            logger.error("gRPC request failed", exception)
            observer.onError(Status.INTERNAL.withDescription("Internal server error").asRuntimeException())
        }
    }

    private fun uuid(value: String, name: String): UUID = try {
        UUID.fromString(value)
    } catch (exception: IllegalArgumentException) {
        invalidRequest("$name must be a UUID")
    }

    private fun instant(value: Timestamp, present: Boolean, name: String): Instant {
        if (!present) invalidRequest("$name is required")
        if (value.seconds !in -62135596800L..253402300799L || value.nanos !in 0..999999999) {
            invalidRequest("$name must be a valid protobuf Timestamp")
        }
        return Instant.ofEpochSecond(value.seconds, value.nanos.toLong())
    }

    private fun Instant.toProto(): Timestamp = Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

    private fun Resource.toProto(): GrpcResource = GrpcResource.newBuilder()
        .setId(id.toString()).setName(name).setKind(kind).build()

    private fun Booking.toProto(): GrpcBooking = GrpcBooking.newBuilder()
        .setId(id.toString()).setResourceId(resourceId.toString()).setBookedBy(bookedBy)
        .setStartsAt(startsAt.toProto()).setEndsAt(endsAt.toProto())
        .setStatus(GrpcBookingStatus.valueOf(status.name)).setCreatedAt(createdAt.toProto())
        .apply { this@toProto.cancelledAt?.let { setCancelledAt(it.toProto()) } }.build()

    companion object {
        val ERROR_CODE: Metadata.Key<String> = Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER)
    }
}
