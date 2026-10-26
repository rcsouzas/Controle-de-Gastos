package br.com.controlegastos;

import br.com.controlegastos.config.PersistentDatabaseStorage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExpenseTrackerApplication {
    public static void main(String[] args) {
        PersistentDatabaseStorage.prepare();
        SpringApplication.run(ExpenseTrackerApplication.class, args);
    }
}