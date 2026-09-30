package dev.nikita.booking

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import dev.nikita.booking.grpc.BookingGrpcService
import dev.nikita.booking.grpc.GrpcServer
import dev.nikita.booking.proto.BookingApiGrpc
import dev.nikita.booking.proto.BookingStatus
import dev.nikita.booking.proto.CancelBookingRequest
import dev.nikita.booking.proto.CreateBookingRequest
import dev.nikita.booking.proto.GetAvailabilityRequest
import dev.nikita.booking.proto.GetBookingRequest
import dev.nikita.booking.proto.GetResourceRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.reflection.v1.ServerReflectionGrpc
import io.grpc.reflection.v1.ServerReflectionRequest
import io.grpc.reflection.v1.ServerReflectionResponse
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["grpc.port=0", "analytics.refresh.enabled=false"])
@Import(BookingApiTest.TestClock::class)
class BookingGrpcTest {
    @Autowired lateinit var server: GrpcServer
    @Autowired lateinit var api: BookingGrpcService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var http: TestRestTemplate
    @Autowired lateinit var mapper: ObjectMapper
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE bookings, idempotency_keys")
        channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    }

    @AfterEach
    fun closeChannel() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `resources booking and availability use the running server`() {
        assertThat(client().listResources(Empty.getDefaultInstance()).resourcesCount).isEqualTo(2)
        assertThat(client().getResource(GetResourceRequest.newBuilder().setId(ROOM).build()).kind).isEqualTo("MEETING_ROOM")
        val created = client("create").createBooking(request())
        assertThat(created.replayed).isFalse()
        assertThat(created.booking.status).isEqualTo(BookingStatus.ACTIVE)
        assertThat(created.booking.hasCancelledAt()).isFalse()
        assertThat(client().getBooking(get(created.booking.id))).isEqualTo(created.booking)
        val availability = client().getAvailability(window("2030-01-02T09:00:00Z", "2030-01-02T12:00:00Z"))
        assertThat(availability.freeList.map { it.startsAt }).containsExactly(time("2030-01-02T09:00:00Z"), time("2030-01-02T11:00:00Z"))
        assertThat(availability.freeList.map { it.endsAt }).containsExactly(time("2030-01-02T10:00:00Z"), time("2030-01-02T12:00:00Z"))
    }

    @Test
    fun `REST creation can be replayed through gRPC`() {
        val response = http.exchange("/api/bookings", HttpMethod.POST, httpRequest("cross-rest", " Nikita "), String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(201)
        val original = mapper.readTree(response.body)
        val replay = client("cross-rest").createBooking(request())
        assertThat(replay.replayed).isTrue()
        assertThat(replay.booking.id).isEqualTo(original["id"].asText())
        assertThat(replay.booking.bookedBy).isEqualTo("Nikita")
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `gRPC creation can be replayed through REST`() {
        val original = client("cross-grpc").createBooking(request().toBuilder().setBookedBy(" Nikita ").build())
        val response = http.exchange("/api/bookings", HttpMethod.POST, httpRequest("cross-grpc"), String::class.java)
        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(response.headers.getFirst("Idempotency-Replayed")).isEqualTo("true")
        assertThat(mapper.readTree(response.body)["id"].asText()).isEqualTo(original.booking.id)
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `retries preserve original response after cancellation`() {
        val original = client("repeat").createBooking(request())
        val cancelled = client().cancelBooking(cancel(original.booking.id))
        assertThat(cancelled.status).isEqualTo(BookingStatus.CANCELLED)
        assertThat(cancelled.hasCancelledAt()).isTrue()
        assertThat(client().cancelBooking(cancel(original.booking.id))).isEqualTo(cancelled)
        val replay = client("repeat").createBooking(request())
        assertThat(replay.replayed).isTrue()
        assertThat(replay.booking).isEqualTo(original.booking)
        assertThat(client().getBooking(get(original.booking.id))).isEqualTo(cancelled)
        assertThat(client("replacement").createBooking(request()).replayed).isFalse()
    }

    @Test
    fun `cancellation frees availability`() {
        val created = client("cancel").createBooking(request())
        assertThat(client().getAvailability(window(START, END)).freeCount).isZero()
        client().cancelBooking(cancel(created.booking.id))
        val free = client().getAvailability(window(START, END)).freeList
        assertThat(free).hasSize(1)
        assertThat(free[0].startsAt).isEqualTo(time(START))
        assertThat(free[0].endsAt).isEqualTo(time(END))
    }

    @Test
    fun `key reuse rejects a different command with a stable error code`() {
        client("same").createBooking(request())
        val exception = expect(Status.Code.ALREADY_EXISTS) {
            client("same").createBooking(request().toBuilder().setBookedBy("Another person").build())
        }
        assertThat(exception.trailers?.get(ERROR_CODE)).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `conflict rolls back the key so it can be retried`() {
        val original = client("occupied").createBooking(request())
        val exception = expect(Status.Code.ALREADY_EXISTS) { client("retry-later").createBooking(request()) }
        assertThat(exception.trailers?.get(ERROR_CODE)).isEqualTo("SLOT_UNAVAILABLE")
        assertThat(count("idempotency_keys")).isEqualTo(1)
        client().cancelBooking(cancel(original.booking.id))
        assertThat(client("retry-later").createBooking(request()).replayed).isFalse()
    }

    @Test
    fun `parallel same-key calls create one booking`() {
        val results = parallel(8) { client("same-key-race").createBooking(request()) }
        assertThat(results.map { it.booking.id }.distinct()).hasSize(1)
        assertThat(results.count { !it.replayed }).isEqualTo(1)
        assertThat(count("bookings")).isEqualTo(1)
        assertThat(count("idempotency_keys")).isEqualTo(1)
    }

    @Test
    fun `parallel competing calls cannot double-book a resource`() {
        val results = parallel(8) { index ->
            runCatching { client("competing-$index").createBooking(request()) }
        }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        results.filter { it.isFailure }.forEach {
            assertThat((it.exceptionOrNull() as StatusRuntimeException).status.code).isEqualTo(Status.Code.ALREADY_EXISTS)
        }
        assertThat(count("bookings")).isEqualTo(1)
        assertThat(count("idempotency_keys")).isEqualTo(1)
    }

    @Test
    fun `same key cannot race with a different payload`() {
        val results = parallel(2) { index ->
            runCatching { client("payload-race").createBooking(request().toBuilder().setBookedBy("Person $index").build()) }
        }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val error = results.single { it.isFailure }.exceptionOrNull() as StatusRuntimeException
        assertThat(error.status.code).isEqualTo(Status.Code.ALREADY_EXISTS)
        assertThat(error.trailers?.get(ERROR_CODE)).isEqualTo("IDEMPOTENCY_KEY_REUSED")
    }

    @Test
    fun `timestamps must be present valid and no more precise than microseconds`() {
        val missing = listOf(request().toBuilder().clearStartsAt().build(), request().toBuilder().clearEndsAt().build())
        missing.forEach { expect(Status.Code.INVALID_ARGUMENT) { client("missing-time").createBooking(it) } }
        val invalid = listOf(
            time(START).toBuilder().setNanos(-1).build(),
            time(START).toBuilder().setNanos(1_000_000_000).build(),
            time(START).toBuilder().setNanos(1).build(),
            Timestamp.newBuilder().setSeconds(-62135596801L).build(),
            Timestamp.newBuilder().setSeconds(253402300800L).build()
        )
        invalid.forEach { expect(Status.Code.INVALID_ARGUMENT) { client("invalid-time").createBooking(request().toBuilder().setStartsAt(it).build()) } }
        expect(Status.Code.INVALID_ARGUMENT) { client().getAvailability(window(START, END).toBuilder().clearFrom().build()) }
        expect(Status.Code.INVALID_ARGUMENT) { client().getAvailability(window(START, END).toBuilder().clearTo().build()) }
        assertThat(count("bookings")).isZero()
        assertThat(count("idempotency_keys")).isZero()
    }

    @Test
    fun `interval validation is shared with REST`() {
        val invalid = listOf(
            request().toBuilder().setEndsAt(time(START)).build(),
            request().toBuilder().setEndsAt(time("2030-01-03T11:00:00Z")).build(),
            request().toBuilder().setStartsAt(time("2029-12-31T10:00:00Z")).setEndsAt(time("2029-12-31T11:00:00Z")).build()
        )
        invalid.forEach { expect(Status.Code.INVALID_ARGUMENT) { client("bad-window").createBooking(it) } }
        expect(Status.Code.INVALID_ARGUMENT) { client().getAvailability(window(START, START)) }
        expect(Status.Code.INVALID_ARGUMENT) { client().getAvailability(window(START, "2030-03-01T00:00:00Z")) }
    }

    @Test
    fun `name and metadata validation are shared with REST`() {
        listOf("", "  ", "\u00a0", "x".repeat(101), " " + "x".repeat(100)).forEach { name ->
            expect(Status.Code.INVALID_ARGUMENT) { client("bad-name").createBooking(request().toBuilder().setBookedBy(name).build()) }
        }
        expect(Status.Code.INVALID_ARGUMENT) { client().createBooking(request()) }
        listOf("", "bad key", "x".repeat(129)).forEach { key ->
            expect(Status.Code.INVALID_ARGUMENT) { client(key).createBooking(request()) }
        }
        assertThat(count("idempotency_keys")).isZero()
    }

    @Test
    fun `malformed ids and missing entities have distinct statuses`() {
        expect(Status.Code.INVALID_ARGUMENT) { client("bad-id").createBooking(request().toBuilder().setResourceId("invalid").build()) }
        expect(Status.Code.INVALID_ARGUMENT) { client().getBooking(get("")) }
        expect(Status.Code.INVALID_ARGUMENT) { client().cancelBooking(cancel("invalid")) }
        expect(Status.Code.INVALID_ARGUMENT) { client().getResource(GetResourceRequest.getDefaultInstance()) }
        val missing = UUID.randomUUID().toString()
        expect(Status.Code.NOT_FOUND) { client("missing-resource").createBooking(request().toBuilder().setResourceId(missing).build()) }
        expect(Status.Code.NOT_FOUND) { client().getBooking(get(missing)) }
        expect(Status.Code.NOT_FOUND) { client().cancelBooking(cancel(missing)) }
        expect(Status.Code.NOT_FOUND) { client().getResource(GetResourceRequest.newBuilder().setId(missing).build()) }
        expect(Status.Code.NOT_FOUND) { client().getAvailability(window(START, END).toBuilder().setResourceId(missing).build()) }
    }

    @Test
    fun `deadline allows a safe retry even if the transaction later commits`() {
        client().listResources(Empty.getDefaultInstance())
        dataSource.connection.use { lock ->
            lock.autoCommit = false
            lock.createStatement().use { it.execute("LOCK TABLE bookings IN ACCESS EXCLUSIVE MODE") }
            try {
                expect(Status.Code.DEADLINE_EXCEEDED) {
                    client("timeout").withDeadlineAfter(150, TimeUnit.MILLISECONDS).createBooking(request())
                }
            } finally {
                lock.rollback()
            }
        }
        val retried = client("timeout").createBooking(request())
        val replayed = client("timeout").createBooking(request())
        assertThat(replayed.replayed).isTrue()
        assertThat(replayed.booking).isEqualTo(retried.booking)
        assertThat(count("bookings")).isEqualTo(1)
    }

    @Test
    fun `reflection describes the booking API`() {
        val result = CompletableFuture<ServerReflectionResponse>()
        val stream = ServerReflectionGrpc.newStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            .serverReflectionInfo(object : StreamObserver<ServerReflectionResponse> {
                override fun onNext(value: ServerReflectionResponse) { result.complete(value) }
                override fun onError(error: Throwable) { result.completeExceptionally(error) }
                override fun onCompleted() = Unit
            })
        stream.onNext(ServerReflectionRequest.newBuilder().setListServices("").build())
        stream.onCompleted()
        assertThat(result.get(5, TimeUnit.SECONDS).listServicesResponse.serviceList.map { it.name })
            .contains("booking.v1.BookingApi")
    }

    @Test
    fun `server lifecycle releases its listener`() {
        val extraServer = GrpcServer(api, 0)
        extraServer.start()
        val extraChannel = ManagedChannelBuilder.forAddress("localhost", extraServer.port).usePlaintext().build()
        try {
            val stub = BookingApiGrpc.newBlockingStub(extraChannel).withDeadlineAfter(5, TimeUnit.SECONDS)
            assertThat(stub.listResources(Empty.getDefaultInstance()).resourcesCount).isEqualTo(2)
            extraServer.stop()
            assertThat(extraServer.isRunning).isFalse()
            expect(Status.Code.UNAVAILABLE) { stub.listResources(Empty.getDefaultInstance()) }
        } finally {
            extraChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            extraServer.stop()
        }
    }

    private fun client(key: String? = null): BookingApiGrpc.BookingApiBlockingStub {
        val stub = BookingApiGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
        if (key == null) return stub
        val metadata = Metadata().apply { put(IDEMPOTENCY_KEY, key) }
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    private fun request(): CreateBookingRequest = CreateBookingRequest.newBuilder()
        .setResourceId(ROOM).setBookedBy("Nikita").setStartsAt(time(START)).setEndsAt(time(END)).build()

    private fun window(from: String, to: String): GetAvailabilityRequest = GetAvailabilityRequest.newBuilder()
        .setResourceId(ROOM).setFrom(time(from)).setTo(time(to)).build()

    private fun get(id: String): GetBookingRequest = GetBookingRequest.newBuilder().setId(id).build()

    private fun cancel(id: String): CancelBookingRequest = CancelBookingRequest.newBuilder().setId(id).build()

    private fun time(value: String): Timestamp = Instant.parse(value).let {
        Timestamp.newBuilder().setSeconds(it.epochSecond).setNanos(it.nano).build()
    }

    private fun httpRequest(key: String, name: String = "Nikita"): HttpEntity<Map<String, String>> = HttpEntity(
        mapOf("resourceId" to ROOM, "bookedBy" to name, "startsAt" to START, "endsAt" to END),
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", key)
        }
    )

    private fun expect(code: Status.Code, action: () -> Unit): StatusRuntimeException =
        assertThrows(StatusRuntimeException::class.java, action).also { assertThat(it.status.code).isEqualTo(code) }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!

    private fun <T> parallel(count: Int, action: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(count).use { executor ->
            val futures = (0 until count).map { index ->
                executor.submit(Callable {
                    start.await()
                    action(index)
                })
            }
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        }
    }

    companion object {
        private const val ROOM = "11111111-1111-1111-1111-111111111111"
        private const val START = "2030-01-02T10:00:00Z"
        private const val END = "2030-01-02T11:00:00Z"
        private val IDEMPOTENCY_KEY = Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_CODE = Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER)

        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
