package br.com.controlegastos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PersistentDatabaseStorage {
    private static final Logger logger = LoggerFactory.getLogger(PersistentDatabaseStorage.class);
    private static final String DATABASE_FILE_SUFFIX = ".mv.db";

    private PersistentDatabaseStorage() {
    }

    public static void prepare() {
        Path userDirectory = Path.of(System.getProperty("user.home"), ".controle-de-gastos", "data");
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        prepare(userDirectory.resolve("gastosdb"), List.of(
                workingDirectory.resolve("data/gastosdb" + DATABASE_FILE_SUFFIX),
                workingDirectory.resolve("data/controle-gastos" + DATABASE_FILE_SUFFIX),
                workingDirectory.resolve("backend/data/gastosdb" + DATABASE_FILE_SUFFIX),
                workingDirectory.resolve("backend/data/controle-gastos" + DATABASE_FILE_SUFFIX)));
    }

    static void prepare(Path databasePath, List<Path> legacyDatabaseFiles) {
        Path databaseFile = Path.of(databasePath + DATABASE_FILE_SUFFIX);
        try {
            Files.createDirectories(databaseFile.getParent());
            if (Files.exists(databaseFile)) {
                return;
            }

            for (Path legacyDatabaseFile : legacyDatabaseFiles) {
                if (Files.isRegularFile(legacyDatabaseFile)) {
                    Files.copy(legacyDatabaseFile, databaseFile);
                    logger.info("Banco H2 existente copiado para armazenamento fora do repositório: {}", databaseFile);
                    return;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível preparar o armazenamento persistente do banco H2", exception);
        }
    }
}
