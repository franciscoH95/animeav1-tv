package com.animeav1.data.local

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Profile::class, FavoriteSeries::class, WatchedEpisode::class, EpisodeProgress::class,
        SeriesPrefs::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun favoriteSeriesDao(): FavoriteSeriesDao
    abstract fun watchedEpisodeDao(): WatchedEpisodeDao
    abstract fun episodeProgressDao(): EpisodeProgressDao
    abstract fun seriesPrefsDao(): SeriesPrefsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** Adds the resume-point table without wiping favorites/watched history. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
                        "`animeSlug` TEXT NOT NULL, " +
                        "`episodeNumber` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`animeSlug`, `episodeNumber`))"
                )
            }
        }

        /** Adds year + status columns to the watchlist so My List can show them. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_series ADD COLUMN year TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorite_series ADD COLUMN status INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** Adds the category (type) column to the watchlist. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_series ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Perfiles. Mete `profileId` en la clave primaria de las tres tablas de estado local.
         *
         * ⚠️ Aquí NO sirve un `ALTER TABLE ... ADD COLUMN`: SQLite no puede cambiar una clave
         * primaria, así que hay que recrear cada tabla, copiar las filas y renombrar. Es la primera
         * migración de este proyecto que mueve datos en vez de solo añadir columnas.
         *
         * Todo lo que ya existía se adopta en el perfil [Profile.DEFAULT_ID] — el usuario que venía
         * usando la app abre la versión nueva y encuentra sus listas donde estaban. Las columnas se
         * enumeran EXPLÍCITAMENTE en el INSERT ... SELECT: un `SELECT *` dependería del orden de las
         * columnas en disco, que no es el del `data class` después de tres migraciones.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profiles` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`colorIndex` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                // El perfil que adopta los datos existentes. Se fija el id a mano (no se deja al
                // AUTOINCREMENT) porque los INSERT de abajo lo referencian como constante.
                db.execSQL(
                    "INSERT INTO `profiles` (`id`, `name`, `colorIndex`, `createdAt`) " +
                        "VALUES (${Profile.DEFAULT_ID}, 'Principal', 0, ${System.currentTimeMillis()})"
                )

                db.execSQL(
                    "CREATE TABLE `favorite_series_new` (" +
                        "`profileId` INTEGER NOT NULL, `slug` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`coverUrl` TEXT NOT NULL, `listType` TEXT NOT NULL, `totalEpisodes` INTEGER NOT NULL, " +
                        "`isFavorite` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "`year` TEXT NOT NULL DEFAULT '', `status` INTEGER NOT NULL DEFAULT -1, " +
                        "`category` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`profileId`, `slug`))"
                )
                db.execSQL(
                    "INSERT INTO `favorite_series_new` " +
                        "(`profileId`,`slug`,`title`,`coverUrl`,`listType`,`totalEpisodes`,`isFavorite`,`addedAt`,`year`,`status`,`category`) " +
                        "SELECT ${Profile.DEFAULT_ID},`slug`,`title`,`coverUrl`,`listType`,`totalEpisodes`,`isFavorite`,`addedAt`,`year`,`status`,`category` " +
                        "FROM `favorite_series`"
                )
                db.execSQL("DROP TABLE `favorite_series`")
                db.execSQL("ALTER TABLE `favorite_series_new` RENAME TO `favorite_series`")

                db.execSQL(
                    "CREATE TABLE `watched_episodes_new` (" +
                        "`profileId` INTEGER NOT NULL, `animeSlug` TEXT NOT NULL, " +
                        "`episodeNumber` INTEGER NOT NULL, `watchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `animeSlug`, `episodeNumber`))"
                )
                db.execSQL(
                    "INSERT INTO `watched_episodes_new` (`profileId`,`animeSlug`,`episodeNumber`,`watchedAt`) " +
                        "SELECT ${Profile.DEFAULT_ID},`animeSlug`,`episodeNumber`,`watchedAt` FROM `watched_episodes`"
                )
                db.execSQL("DROP TABLE `watched_episodes`")
                db.execSQL("ALTER TABLE `watched_episodes_new` RENAME TO `watched_episodes`")

                db.execSQL(
                    "CREATE TABLE `episode_progress_new` (" +
                        "`profileId` INTEGER NOT NULL, `animeSlug` TEXT NOT NULL, " +
                        "`episodeNumber` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `animeSlug`, `episodeNumber`))"
                )
                db.execSQL(
                    "INSERT INTO `episode_progress_new` (`profileId`,`animeSlug`,`episodeNumber`,`positionMs`,`durationMs`,`updatedAt`) " +
                        "SELECT ${Profile.DEFAULT_ID},`animeSlug`,`episodeNumber`,`positionMs`,`durationMs`,`updatedAt` FROM `episode_progress`"
                )
                db.execSQL("DROP TABLE `episode_progress`")
                db.execSQL("ALTER TABLE `episode_progress_new` RENAME TO `episode_progress`")
            }
        }

        /**
         * Identidad estable de perfil. Añade `profiles.uuid` y le pone valor a los que ya existían.
         *
         * Aquí sí basta un `ALTER TABLE ... ADD COLUMN`: no se toca ninguna clave primaria, así que
         * **las tres tablas de estado no se recrean** (a diferencia de la 7→8). Siguen refiriéndose
         * al perfil por su `id` local, que es lo correcto dentro del aparato; el uuid solo existe
         * para que la copia de seguridad sea portable entre aparatos.
         *
         * ⚠️ El orden importa: ADD COLUMN deja todas las filas con `''`, así que el índice ÚNICO
         * tiene que crearse **después** del relleno o chocarían entre ellas.
         *
         * El uuid se genera en SQL con `randomblob`, que SQLite evalúa **por fila**, en vez de
         * iterando desde Kotlin: así el relleno va dentro de la misma transacción que la migración y
         * no queda ninguna ventana con perfiles sin identidad. Son 128 bits aleatorios con forma de
         * UUID; no llevan los bits de versión/variante de RFC 4122, y para lo que hace falta —ser
         * único— da igual.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE `profiles` SET `uuid` = lower(" +
                        "hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-' || " +
                        "hex(randomblob(2)) || '-' || hex(randomblob(2)) || '-' || " +
                        "hex(randomblob(6))) WHERE `uuid` = ''"
                )
                // El nombre del índice es el que genera Room para `indices = [Index("uuid", unique)]`;
                // si no coincide, Room detecta el esquema como distinto y lanza al abrir.
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profiles_uuid` ON `profiles` (`uuid`)")
            }
        }

        /**
         * Preferencia de pista/fuente por serie y perfil. Tabla NUEVA: no se toca ninguna de las que
         * ya existen, así que no hay que recrear nada ni hay riesgo para los datos del usuario.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_prefs` (" +
                        "`profileId` INTEGER NOT NULL, `slug` TEXT NOT NULL, " +
                        "`audio` TEXT NOT NULL, `server` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `slug`))"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "animeav1.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .apply {
                        // ⚠️ Borrar la base de datos SOLO en debug. `fallbackToDestructiveMigration`
                        // convierte un despiste (subir `version` sin escribir su MIGRATION_x_y) en
                        // pérdida DEFINITIVA y silenciosa de listas, historial y puntos de
                        // reanudación: la app sigue arrancando y nadie se entera hasta que el
                        // usuario ve Mi Lista vacía. Sin él, Room lanza al abrir la base de datos:
                        // se nota enseguida, pero los datos siguen en disco y se recuperan
                        // publicando la migración que faltaba. En debug sí interesa borrar, que es
                        // lo cómodo mientras se itera el esquema.
                        if (isDebuggable(context)) fallbackToDestructiveMigration()
                    }
                    .build().also { INSTANCE = it }
            }

        private fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
