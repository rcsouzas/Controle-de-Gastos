package br.com.controlegastos.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentDatabaseStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesLegacyDatabaseOnlyWhenExternalDatabaseDoesNotExist() throws Exception {
        Path legacyDatabase = temporaryDirectory.resolve("backend/data/gastosdb.mv.db");
        Files.createDirectories(legacyDatabase.getParent());
        Files.writeString(legacyDatabase, "existing-user-data");
        Path databasePath = temporaryDirectory.resolve("home/.controle-de-gastos/data/gastosdb");

        PersistentDatabaseStorage.prepare(databasePath, List.of(legacyDatabase));

        Path externalDatabase = Path.of(databasePath + ".mv.db");
        assertThat(Files.readString(externalDatabase)).isEqualTo("existing-user-data");
        assertThat(legacyDatabase).exists();

        Files.writeString(legacyDatabase, "new-version-must-not-overwrite-user-data");
        PersistentDatabaseStorage.prepare(databasePath, List.of(legacyDatabase));

        assertThat(Files.readString(externalDatabase)).isEqualTo("existing-user-data");
    }
}
