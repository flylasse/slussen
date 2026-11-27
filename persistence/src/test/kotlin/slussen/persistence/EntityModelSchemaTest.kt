package slussen.persistence

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.start.SqliteDatabaseCheck.ValueColumn
import java.io.File

/**
 * `slussen.db` against the entity model (ADR-002 §2): the tables of the current committed schema version, created on
 * SQLite itself, are compared with `docs/entity_model.md` as it is written. Every entity that ADR-002 §1 puts into
 * `slussen.db` is one table with exactly its attributes, each with the optionality, the storage form, the foreign key and
 * the uniqueness the entity model states, and no table has a column the entity model does not have. The technical tables
 * are compared with the columns ADR-002 §2 lists. The `Values:` of the entity model are compared with the logical check
 * of ADR-002 §8 step 1 ([SlussenStorage.MAIN_VALUES]).
 */
class EntityModelSchemaTest {
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:").also {
        RoomSchemas.create(SlussenDatabase::class, RoomSchemas.committedVersions(SlussenDatabase::class).max(), it)
    }

    @AfterEach
    fun close() = connection.close()

    /** One attribute of an entity as its table of the entity model states it. */
    private data class Attribute(val name: String, val dataType: String, val rules: String) {
        val optional = rules.startsWith("Optional")
        val primaryKey = "Primary Key" in rules
        val unique = rules.split("Values:").first().split(",").any { it.trim() == "Unique" }
        val references: String? = Regex("""Foreign Key \(([A-Z_]+)\.id\)""").find(rules)?.groupValues?.get(1)
        val values: List<String>? = rules.takeIf { "Values:" in it }?.substringAfter("Values:")?.split(",")?.map { it.trim() }
    }

    /** Every entity of the entity model with its attributes, read from the attribute table under its `### NAME` heading. */
    private val entityModel: Map<String, List<Attribute>> by lazy {
        val file = File(checkNotNull(System.getProperty("slussen.entityModel")) { "The build script sets slussen.entityModel for every unit test task" })
        val entities = linkedMapOf<String, MutableList<Attribute>>()
        var entity: String? = null
        file.readLines().forEach { line ->
            val heading = Regex("""^### ([A-Z_]+)$""").find(line)
            when {
                heading != null -> entity = heading.groupValues[1].also { entities[it] = mutableListOf() }
                line.startsWith("## ") -> entity = null
                line.startsWith("|") && entity != null -> {
                    val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                    assertEquals(5, cells.size, "an attribute line of $entity has five cells: $line")
                    if (cells[0] != "Attribute" && !cells[0].startsWith("---")) entities.getValue(entity) += Attribute(cells[0], cells[2], cells[4])
                }
            }
        }
        entities
    }

    /** The entities ADR-002 §1 keeps in `device.db` and `journal.db`, which are not tables of `slussen.db`. */
    private val notInMain = setOf("SERVICE_CREDENTIAL", "BANK_CONSENT", "BANK_SESSION", "CONSENT_REQUEST", "OFFERED_BANK_ACCOUNT", "DECISION_JOURNAL_ENTRY")

    /** The table of an entity: its name in lower case, and `recovery_check` for First Upload Matching (ADR-002 §2, S-41). */
    private fun tableOf(entity: String) = if (entity == "FIRST_UPLOAD_MATCHING") "recovery_check" else entity.lowercase()

    private val mainEntities: Map<String, List<Attribute>> get() = entityModel.filterKeys { it !in notInMain }

    /**
     * The technical tables of ADR-002 §2 in `slussen.db` with their columns, in the order the ADR lists them; the header
     * line of `upload_plan_item` carries the plan's captured `state_version` and `change_cursor`.
     */
    private val technicalTables = mapOf(
        "budget_read_state" to listOf("budget_id", "change_cursor", "period_read_owed"),
        "coordinator_state" to listOf("id", "mode", "upload_plan_id", "state_version", "scheduled_fetch_deferred", "comparison_pending"),
        "upload_plan_item" to listOf(
            "upload_plan_id", "position", "write_kind", "bank_transaction_id", "budget_account_id", "import_identifier", "occurrence", "booked_date",
            "amount", "payee_sent", "memo_sent", "cleared", "transfer_budget_account_id", "clear_counterpart", "transfer_counterpart_id",
            "counterpart_reference", "reused_sent_record_id", "reused_outcome", "state_version", "change_cursor",
        ),
    )

    private fun query(sql: String): List<List<String?>> = RoomSchemas.rows(connection, sql)

    private fun tables(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'").map { it.single()!! }.toSet()

    /** The columns of [table] as (name, type, not null, part of the primary key, default). */
    private fun columnsOf(table: String) = query("SELECT name, type, \"notnull\", pk, dflt_value FROM pragma_table_info('$table')")

    @Test
    @DisplayName("ADR-002 §2: the entity model is read in full: every entity ADR-002 §1 names for slussen.db is among its headings, each with an attribute table that starts with its identifier or its keys")
    fun theEntityModelIsRead() {
        assertTrue(entityModel.keys.containsAll(notInMain), "${entityModel.keys}")
        assertTrue(
            mainEntities.keys.containsAll(
                listOf("BUDGET", "BANK_ACCOUNT", "ACCOUNT_MAPPING", "BANK_TRANSACTION", "REVIEW_DECISION", "TRANSFER", "UPLOAD_PLAN", "ELIGIBLE_TRANSACTION", "SENT_RECORD", "TRANSFER_COUNTERPART", "TRANSACTION_FILE", "FIRST_UPLOAD_MATCHING", "OPERATOR_SETTINGS", "TRANSFER_DESCRIPTION"),
            ),
            "${mainEntities.keys}",
        )
        mainEntities.forEach { (entity, attributes) -> assertTrue(attributes.isNotEmpty() && attributes.first().primaryKey, "$entity: $attributes") }
    }

    @Test
    @DisplayName("ADR-002 §2: slussen.db holds one table per entity of the entity model that ADR-002 §1 puts there, the three technical tables of ADR-002 §2, and no other table")
    fun everyEntityIsOneTableAndNoTableIsWithoutAnEntity() {
        assertEquals((mainEntities.keys.map(::tableOf) + technicalTables.keys).toSortedSet(), tables().toSortedSet())
    }

    @Test
    @DisplayName("ADR-002 §2: each table has exactly the attributes of its entity as columns, none more and none fewer; an Optional attribute may be empty and every other must be filled; a String is stored as TEXT and every other data type as INTEGER; no column fills in a value by itself")
    fun everyTableHasExactlyTheAttributesOfItsEntity() {
        val problems = mainEntities.flatMap { (entity, attributes) ->
            val table = tableOf(entity)
            val columns = columnsOf(table).associateBy { it[0]!! }
            val names = attributes.map { it.name }
            (columns.keys - names.toSet()).map { "$table.$it is a column the entity model does not have" } +
                (names - columns.keys).map { "$table lacks the attribute $it" } +
                attributes.mapNotNull { attribute ->
                    val column = columns[attribute.name] ?: return@mapNotNull null
                    val expected = listOf(
                        attribute.name,
                        if (attribute.dataType == "String") "TEXT" else "INTEGER",
                        if (attribute.optional) "0" else "1",
                        null,
                    )
                    val found = listOf(column[0], column[1], column[2], column[4])
                    val key = (column[3] != "0") == attribute.primaryKey
                    if (found == expected && key) null else "$table.${attribute.name}: the entity model gives $expected (primary key: ${attribute.primaryKey}), the table has $column"
                }
        }
        assertEquals(emptyList<String>(), problems, problems.joinToString("\n"))
    }

    @Test
    @DisplayName("ADR-002 §2: every Foreign Key of the entity model within slussen.db is a foreign key of its column to the identifier of the table named, and every Unique attribute has a unique index of its own")
    fun everyForeignKeyAndUniqueOfTheEntityModelIsInTheSchema() {
        val problems = mainEntities.flatMap { (entity, attributes) ->
            val table = tableOf(entity)
            val foreignKeys = query("SELECT \"from\", \"table\", \"to\" FROM pragma_foreign_key_list('$table')").map { Triple(it[0], it[1], it[2]) }
            val uniqueIndices = query("SELECT name FROM pragma_index_list('$table') WHERE \"unique\" = 1")
                .map { index -> query("SELECT name FROM pragma_index_info('${index.single()}')").map { it.single() } }
            attributes.flatMap { attribute ->
                listOfNotNull(
                    attribute.references?.takeIf { Triple<String?, String?, String?>(attribute.name, tableOf(it), "id") !in foreignKeys }
                        ?.let { "$table.${attribute.name} is no foreign key to ${tableOf(it)}.id" },
                    "$table.${attribute.name} has no unique index of its own".takeIf { attribute.unique && listOf<String?>(attribute.name) !in uniqueIndices },
                )
            }
        }
        assertEquals(emptyList<String>(), problems, problems.joinToString("\n"))
    }

    @Test
    @DisplayName("ADR-002 §2: every reference of a slussen.db table names the identifier of a table that exists, has an index that starts with it, and changes or removes nothing by itself (NO ACTION)")
    fun everyReferenceIsToAStoredTableAndIndexed() {
        val problems = tables().flatMap { table ->
            val indices = query("SELECT name FROM pragma_index_list('$table')").map { index -> query("SELECT name FROM pragma_index_info('${index.single()}')").map { it.single() } } +
                listOf(columnsOf(table).filter { it[3] != "0" }.sortedBy { it[3]!!.toInt() }.map { it[0] })
            query("SELECT \"from\", \"table\", \"to\", on_update, on_delete FROM pragma_foreign_key_list('$table')").mapNotNull { key ->
                when {
                    key[1] !in tables() -> "$table.${key[0]} refers to ${key[1]}, which is no table"
                    key[2] != "id" -> "$table.${key[0]} refers to ${key[1]}.${key[2]}, not to its identifier"
                    key[3] != "NO ACTION" || key[4] != "NO ACTION" -> "$table.${key[0]} acts on a change or removal of ${key[1]}: ${key[3]}, ${key[4]}"
                    indices.none { it.firstOrNull() == key[0] } -> "$table.${key[0]} has no index that starts with it"
                    else -> null
                }
            }
        }
        assertEquals(emptyList<String>(), problems, problems.joinToString("\n"))
    }

    @Test
    @DisplayName("ADR-002 §2: the technical tables budget_read_state, coordinator_state and upload_plan_item have exactly the columns ADR-002 §2 lists; an upload_plan_item is one line of one Upload Plan at its position, its header line holds the captured state_version and change_cursor, and every value a write does not have may be empty")
    fun theTechnicalTablesHaveTheColumnsOfAdr002() {
        technicalTables.forEach { (table, columns) -> assertEquals(columns, columnsOf(table).map { it[0] }, table) }
        val item = columnsOf("upload_plan_item").associateBy { it[0] }
        assertEquals(listOf("upload_plan_id" to "1", "position" to "2"), item.values.filter { it[3] != "0" }.map { it[0] to it[3] })
        assertEquals(setOf("upload_plan_id", "position", "write_kind"), item.values.filter { it[2] == "1" }.map { it[0] }.toSet())
        assertEquals(
            setOf("write_kind", "import_identifier", "payee_sent", "memo_sent", "counterpart_reference", "reused_outcome", "change_cursor"),
            item.values.filter { it[1] == "TEXT" }.map { it[0] }.toSet(),
        )
        assertEquals(
            setOf(
                Triple("upload_plan_id", "upload_plan", "id"), Triple("bank_transaction_id", "bank_transaction", "id"),
                Triple("budget_account_id", "budget_account", "id"), Triple("transfer_budget_account_id", "budget_account", "id"),
                Triple("transfer_counterpart_id", "transfer_counterpart", "id"), Triple("reused_sent_record_id", "sent_record", "id"),
            ),
            query("SELECT \"from\", \"table\", \"to\" FROM pragma_foreign_key_list('upload_plan_item')").map { Triple(it[0], it[1], it[2]) }.toSet(),
        )
    }

    @Test
    @DisplayName("ADR-002 §8 step 1: the logical check at every start knows every Values column of the entity model in slussen.db with exactly its allowed texts, the mode of the coordinator and the two value columns of upload_plan_item, and no other column")
    fun everyValuesColumnOfTheEntityModelIsCheckedAtTheStart() {
        val ofTheEntityModel = mainEntities.flatMap { (entity, attributes) ->
            attributes.mapNotNull { attribute -> attribute.values?.let { ValueColumn(tableOf(entity), attribute.name, it) } }
        }
        val sentRecordOutcomes = ofTheEntityModel.single { it.table == "sent_record" && it.column == "outcome" }.allowed
        val technical = listOf(
            ValueColumn("coordinator_state", "mode", listOf("Normal", "Plan Open", "Uploading")),
            ValueColumn("upload_plan_item", "write_kind", listOf("Header", "Create", "LinkedTransfer", "ClearCounterpart")),
            ValueColumn("upload_plan_item", "reused_outcome", sentRecordOutcomes),
        )
        fun sorted(columns: List<ValueColumn>) = columns.sortedWith(compareBy({ it.table }, { it.column })).map { Triple(it.table, it.column, it.allowed.toSortedSet()) }

        assertEquals(sorted(ofTheEntityModel + technical), sorted(SlussenStorage.MAIN_VALUES))
        SlussenStorage.MAIN_VALUES.forEach { column -> assertEquals(column.allowed.distinct(), column.allowed, "$column") }
        SlussenStorage.MAIN_VALUES.forEach { column -> assertTrue(columnsOf(column.table).any { it[0] == column.column && it[1] == "TEXT" }, "$column") }
    }
}
