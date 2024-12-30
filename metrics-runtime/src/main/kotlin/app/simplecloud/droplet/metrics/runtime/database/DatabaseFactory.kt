package app.simplecloud.droplet.metrics.runtime.database

import org.jooq.impl.DSL

object DatabaseFactory {
    fun createDatabase(databaseUrl: String): Database {
        val databaseContext = DSL.using(databaseUrl)
        return Database(databaseContext)
    }
}
