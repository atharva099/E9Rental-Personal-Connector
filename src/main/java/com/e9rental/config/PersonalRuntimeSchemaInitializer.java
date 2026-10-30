package com.e9rental.config;

import com.e9rental.connector.UnifiedRepository;
import java.nio.file.*;
import java.util.Set;

/** Creates only the empty unified schema during explicitly authorized fresh Personal initialization. */
public final class PersonalRuntimeSchemaInitializer {
    private static final Set<String> INITIAL_FILES=Set.of(
            "local-api-token.key","token-encryption.key","personal-tenant-id.txt");

    private PersonalRuntimeSchemaInitializer() {}

    public static void main(String[] args) {
        try {
            if (args.length != 0 || !"true".equals(System.getenv("PERSONAL_RUNTIME_INITIALIZATION_APPROVED"))) reject();
            initializeFreshRuntime(
                    Path.of(required("PERSONAL_RUNTIME_DIR")),
                    Path.of(required("CHALLENGE_RUNTIME_DIR")),
                    required("PERSONAL_TENANT_ID"),
                    required("CHALLENGE_TENANT_ID"), true);
            System.out.println("PASS: empty Personal unified schema initialized; zero records.");
        } catch (Exception failure) {
            System.err.println("Personal empty-schema initialization denied or incomplete; preserve runtime for manual review.");
            System.exit(1);
        }
    }

    static void initializeFreshRuntime(Path runtime, Path challengeRuntime, String tenant,
            String challengeTenant, boolean explicitlyAuthorized) throws Exception {
        if (!explicitlyAuthorized || runtime == null || challengeRuntime == null ||
                tenant == null || !tenant.matches("personal-[a-zA-Z0-9-]{12,90}") ||
                tenant.equals(challengeTenant == null ? "practice-local" : challengeTenant.strip())) reject();

        Path root=runtime.toAbsolutePath().normalize();
        Path challenge=challengeRuntime.toAbsolutePath().normalize();
        if (root.toString().contains(";") || overlaps(root,challenge) ||
                !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) reject();
        for (Path path=root;path!=null;path=path.getParent()) {
            if (Files.isSymbolicLink(path) || (Files.exists(path) && !path.toRealPath().equals(path))) reject();
        }

        try (var entries=Files.list(root)) {
            if (entries.anyMatch(path -> !INITIAL_FILES.contains(path.getFileName().toString()))) reject();
        }
        for (String name:INITIAL_FILES) {
            Path file=root.resolve(name);
            if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)==0 || Files.isSymbolicLink(file)) reject();
        }
        if (!tenant.equals(Files.readString(root.resolve("personal-tenant-id.txt")).strip())) reject();

        // A new initialization must never adopt an old grant or an existing index.
        if (Files.exists(root.resolve("gmail-token.enc"),LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(root.resolve("unified"),LinkOption.NOFOLLOW_LINKS)) reject();

        String database=root.resolve("unified/index").toString().replace('\\','/');
        UnifiedRepository.initializeEmptySchema("jdbc:h2:file:"+database+";DB_CLOSE_ON_EXIT=FALSE");

        Path unified=root.resolve("unified");
        Path dbFile=unified.resolve("index.mv.db");
        if (!Files.isDirectory(unified,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(unified) ||
                !Files.isRegularFile(dbFile,LinkOption.NOFOLLOW_LINKS) || Files.size(dbFile)==0 || Files.isSymbolicLink(dbFile)) reject();
        try (var entries=Files.list(unified)) {
            if (entries.anyMatch(path -> !path.equals(dbFile))) reject();
        }
    }

    private static boolean overlaps(Path left, Path right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalStateException();
        return value;
    }

    private static void reject() { throw new IllegalStateException("Personal schema initialization preconditions failed"); }
}
