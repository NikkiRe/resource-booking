package dev.nikita.booking

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ResourceRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val mapper = RowMapper { rs, _ ->
        Resource(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("kind"))
    }

    fun list(): List<Resource> = jdbc.query("SELECT * FROM resources ORDER BY name", mapper)

    fun find(id: UUID): Resource? = jdbc.query(
        "SELECT * FROM resources WHERE id = :id", mapOf("id" to id), mapper
    ).firstOrNull()
}
