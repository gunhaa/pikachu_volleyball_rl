package pika.analysis

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * 분석 DB 연결. (FR-8, NFR-5, plan.md §6.2)
 *
 * 접속 정보는 `--db-url` · 환경 변수 `PIKA_DB_URL` · 기본값 순이다. 기본값은
 * `deploy/compose` 의 로컬 개발용 고정값이다 (127.0.0.1 에만 묶여 있다).
 */
object Db {

    /** `deploy/mysql/init/001_schema.sql` 의 `schema_version` 과 같아야 한다. */
    const val SCHEMA_VERSION = 1

    const val DEFAULT_URL = "jdbc:mysql://127.0.0.1:3306/pika"
    private const val PARAMS = "useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"

    data class Config(val url: String, val user: String, val password: String) {
        companion object {
            fun from(opts: Main.Options? = null, url: String? = null): Config = Config(
                url = url ?: opts?.str("--db-url") ?: System.getenv("PIKA_DB_URL") ?: DEFAULT_URL,
                user = opts?.str("--db-user") ?: System.getenv("PIKA_DB_USER") ?: "pika",
                password = opts?.str("--db-password") ?: System.getenv("PIKA_DB_PASSWORD") ?: "pika",
            )
        }
    }

    fun connect(config: Config): Connection {
        val url = if ('?' in config.url) "${config.url}&$PARAMS" else "${config.url}?$PARAMS"
        return DriverManager.getConnection(url, config.user, config.password)
    }

    /** 연결 + 스키마 버전 대조. 다르면 적재하지 않는다 — 볼륨을 지우고 다시 만들라는 뜻이다. */
    fun open(config: Config): Connection {
        val conn = connect(config)
        try {
            checkSchema(conn)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
        return conn
    }

    fun checkSchema(conn: Connection) {
        val found = conn.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_version").use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
        check(found == SCHEMA_VERSION) {
            "DB 스키마 버전이 $found 입니다 (코드는 $SCHEMA_VERSION). DB 는 캐시다 — " +
                "볼륨을 지우고(docker compose … down -v) 다시 적재하세요 (plan.md §6.3)."
        }
    }

    /** 스키마 파일을 그대로 실행한다. 테스트가 빈 DB 를 만들 때 쓴다. */
    fun applySchema(conn: Connection, schema: Path) {
        val sql = Files.readString(schema).lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        conn.createStatement().use { st ->
            for (stmt in sql.split(Regex(";\\s*\\n"))) if (stmt.isNotBlank()) st.execute(stmt.trim().removeSuffix(";"))
        }
    }

    /** 스키마의 테이블을 전부 지운다 (테스트용). */
    fun dropAll(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("SET FOREIGN_KEY_CHECKS = 0")
            for (t in listOf("power_hit", "rally", "game", "participant", "match_set", "schema_version")) {
                st.execute("DROP TABLE IF EXISTS $t")
            }
            st.execute("SET FOREIGN_KEY_CHECKS = 1")
        }
    }

    /** 트랜잭션. 예외면 전부 되돌린다. */
    inline fun <T> tx(conn: Connection, block: () -> T): T {
        val auto = conn.autoCommit
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = auto
        }
    }
}
