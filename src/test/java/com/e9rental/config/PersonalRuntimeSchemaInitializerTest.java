package com.e9rental.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalRuntimeSchemaInitializerTest {
    @TempDir Path temp;

    private Path freshRuntime() throws Exception {
        Path root=temp.resolve("personal-runtime");
        Files.createDirectory(root);
        Files.writeString(root.resolve("local-api-token.key"),Base64.getEncoder().encodeToString(new byte[32]));
        byte[] key=new byte[32];key[0]=1;
        Files.writeString(root.resolve("token-encryption.key"),Base64.getEncoder().encodeToString(key));
        Files.writeString(root.resolve("personal-tenant-id.txt"),"personal-1234567890abcdef1234567890abcdef");
        return root;
    }

    @Test void createsOnlyAnEmptyUnifiedSchemaForExplicitFreshInitialization() throws Exception {
        Path root=freshRuntime();
        PersonalRuntimeSchemaInitializer.initializeFreshRuntime(root,temp.resolve("challenge"),
                "personal-1234567890abcdef1234567890abcdef","practice-local",true);

        Path dbFile=root.resolve("unified/index.mv.db");
        assertThat(dbFile).isRegularFile();
        assertThat(Files.size(dbFile)).isPositive();
        assertThat(root.resolve("gmail-token.enc")).doesNotExist();
        try(var files=Files.list(root.resolve("unified"))) {
            assertThat(files.map(p->p.getFileName().toString()).toList()).containsExactly("index.mv.db");
        }
        try(var connection=DriverManager.getConnection("jdbc:h2:file:"+root.resolve("unified/index").toString().replace('\\','/')+
                    ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;TRACE_LEVEL_FILE=0;TRACE_LEVEL_SYSTEM_OUT=0","sa","");
            var query=connection.createStatement().executeQuery("SELECT COUNT(*) FROM unified_records")) {
            assertThat(query.next()).isTrue();
            assertThat(query.getLong(1)).isZero();
        }
        try(var files=Files.list(root)) {
            assertThat(files.map(p->p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("local-api-token.key","token-encryption.key","personal-tenant-id.txt","unified");
        }
    }

    @Test void refusesExistingDatabaseWithoutChangingIt() throws Exception {
        Path root=freshRuntime();
        Path unified=root.resolve("unified");Files.createDirectory(unified);
        Path existing=unified.resolve("index.mv.db");byte[] sentinel="synthetic-existing-database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(existing,sentinel);
        assertThatThrownBy(()->PersonalRuntimeSchemaInitializer.initializeFreshRuntime(root,temp.resolve("challenge"),
                "personal-1234567890abcdef1234567890abcdef","practice-local",true)).isInstanceOf(Exception.class);
        assertThat(Files.readAllBytes(existing)).containsExactly(sentinel);
        try(var files=Files.list(unified)) {
            assertThat(files.map(p->p.getFileName().toString()).toList()).containsExactly("index.mv.db");
        }
    }

    @Test void rejectsExistingTokenUnexpectedFilesTenantCollisionAndUnauthorizedInvocation() throws Exception {
        Path root=freshRuntime();
        Files.writeString(root.resolve("gmail-token.enc"),"synthetic-token");
        assertThatThrownBy(()->PersonalRuntimeSchemaInitializer.initializeFreshRuntime(root,temp.resolve("challenge"),
                "personal-1234567890abcdef1234567890abcdef","practice-local",true)).isInstanceOf(Exception.class);
        Files.delete(root.resolve("gmail-token.enc"));
        Files.writeString(root.resolve("unexpected.txt"),"synthetic");
        assertThatThrownBy(()->PersonalRuntimeSchemaInitializer.initializeFreshRuntime(root,temp.resolve("challenge"),
                "personal-1234567890abcdef1234567890abcdef","practice-local",true)).isInstanceOf(Exception.class);
        Files.delete(root.resolve("unexpected.txt"));
        assertThatThrownBy(()->PersonalRuntimeSchemaInitializer.initializeFreshRuntime(root,temp.resolve("challenge"),
                "personal-1234567890abcdef1234567890abcdef","personal-1234567890abcdef1234567890abcdef",true)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->PersonalRuntimeSchemaInitializer.initializeFreshRuntime(root,temp.resolve("challenge"),
                "personal-1234567890abcdef1234567890abcdef","practice-local",false)).isInstanceOf(Exception.class);
        assertThat(root.resolve("unified/index.mv.db")).doesNotExist();
    }
}
