package com.smartinbox.processor.service;

import com.smartinbox.processor.entity.TaskItem;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TaskSchemaMigrationTest {
    @Test void ddlUpdatePreservesLegacyThreeColumnTasks() throws Exception {
        String url = "jdbc:h2:mem:legacy-task-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("create table task_item(id varchar(80) primary key, text varchar(300) not null, created_at bigint not null)");
            statement.execute("insert into task_item values('legacy','Preserved old task',1700000000000)");
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url).applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "").applySetting("hibernate.hbm2ddl.auto", "update")
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy").build();
        try (var factory = new MetadataSources(registry).addAnnotatedClass(TaskItem.class).buildMetadata().buildSessionFactory(); var session = factory.openSession()) {
            var task = session.find(TaskItem.class, "legacy");
            assertEquals("Preserved old task", task.getText()); assertEquals("OPEN", task.getStatus()); assertEquals("NORMAL", task.getPriority()); assertEquals(0L, task.getVersion());
            session.beginTransaction(); task.setStatus("COMPLETED"); task.setCompletedAt(1750000000000L); session.getTransaction().commit();
            session.clear(); assertEquals("COMPLETED", session.find(TaskItem.class, "legacy").getStatus());
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
}
