package de.vvwt.tm.infrastructure;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Restricts Spring Boot's auto-configured Flyway to root-level migration files only (i.e., files
 * directly in {@code classpath:db/migration/}, not in per-module sub-directories).
 *
 * <h2>Problem (E15S05)</h2>
 *
 * <p>DEC-21 establishes the convention {@code db/migration/{module}/V1__*.sql} for per-module
 * Flyway migrations. These are applied exclusively by {@link
 * de.vvwt.tm.tenant.internal.PerTenantFlywayRunner} — NOT by Spring Boot's auto-configured Flyway.
 *
 * <p>Spring Boot's Flyway scans {@code classpath:db/migration} <em>recursively</em>, discovering
 * per-module files (e.g., {@code db/migration/auth/V1__admin_credentials.sql}) and reporting a
 * version conflict with root-level {@code V1__initial_schema.sql}. This customizer prevents that
 * conflict by providing a {@link ResourceProvider} that only returns SQL files at the root level of
 * {@code db/migration/} — files in sub-directories are silently excluded from Spring Boot's startup
 * Flyway scan.
 *
 * <h2>Architecture (DEC-20, DEC-21)</h2>
 *
 * <ul>
 *   <li><b>Spring Boot startup Flyway (this customizer):</b> root-level legacy migrations only
 *       ({@code V1..V16__*.sql} directly in {@code db/migration/}). Used during the parallel
 *       development phase against the shared Spring Boot datasource.
 *   <li><b>PerTenantFlywayRunner:</b> per-module migrations ({@code
 *       db/migration/{module}/V1__*.sql}), applied at tenant-creation time against per-tenant H2
 *       files.
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>This bean is a parallel-development-phase artifact. At E15S07 atomic cutover, the legacy root
 * migrations are deleted and Spring Boot Flyway becomes a no-op. At that point ({@code
 * spring.flyway.enabled=false}) this customizer can be removed.
 *
 * @see de.vvwt.tm.tenant.internal.PerTenantFlywayRunner
 * @see <a
 *     href="../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S05.story.md">Story
 *     E15S05</a>
 * @since E15S05
 */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
public class FlywayRootMigrationsCustomizer {

    /**
     * Registers the {@link RootLevelOnlyResourceProvider} as a {@link
     * FlywayConfigurationCustomizer} bean, which restricts Flyway to root-level migration files
     * only.
     *
     * <p>Registered as a Spring Boot auto-configuration so it is active in ALL Spring contexts,
     * including partial {@code @ApplicationModuleTest} contexts (which only load beans from the
     * tested module's package but DO load all Spring Boot auto-configurations).
     */
    @Bean
    public FlywayConfigurationCustomizer flywayRootOnlyCustomizer() {
        return configuration -> configuration.resourceProvider(new RootLevelOnlyResourceProvider());
    }

    /** The classpath path prefix for the root migration directory (no trailing slash). */
    static final String MIGRATION_CLASSPATH_PATH = "db/migration";

    /**
     * A Flyway {@link ResourceProvider} that scans only the root level of {@code
     * classpath:db/migration/}, excluding files in any sub-directory.
     *
     * <p>Supports both exploded-classpath (file system) and JAR-packaged deployments.
     */
    public static final class RootLevelOnlyResourceProvider implements ResourceProvider {

        @Override
        public LoadableResource getResource(String name) {
            // Individual resource lookup by name — used by Flyway for repeatable migrations
            // and callback scripts. Return the file if it exists at root level.
            ClassLoader cl = contextClassLoader();
            URL url = cl.getResource(MIGRATION_CLASSPATH_PATH + "/" + name);
            if (url == null) return null;
            // Only return if this is a root-level file (no sub-directory in name)
            if (name.contains("/")) return null;
            return new ClasspathLoadableResource(name, url);
        }

        @Override
        public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
            List<LoadableResource> results = new ArrayList<>();
            ClassLoader cl = contextClassLoader();
            try {
                Enumeration<URL> urls = cl.getResources(MIGRATION_CLASSPATH_PATH);
                while (urls.hasMoreElements()) {
                    URL baseUrl = urls.nextElement();
                    collectRootLevelFiles(baseUrl, prefix, suffixes, results, cl);
                }
            } catch (IOException e) {
                // If scanning fails, return what we have (Flyway will handle empty gracefully)
            }
            return results;
        }

        /**
         * Collects SQL files at the root level of the migration directory from the given URL.
         * Supports both file-system (exploded) and JAR-based classpaths.
         */
        private static void collectRootLevelFiles(
                URL baseUrl,
                String prefix,
                String[] suffixes,
                List<LoadableResource> results,
                ClassLoader cl)
                throws IOException {

            String protocol = baseUrl.getProtocol();

            if ("file".equals(protocol)) {
                // Exploded classpath (IDE / Maven surefire / local run)
                File dir = new File(baseUrl.getFile());
                if (!dir.isDirectory()) return;
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File file : files) {
                    if (!file.isFile()) continue; // skip sub-directories
                    String filename = file.getName();
                    if (!matchesPrefixAndSuffixes(filename, prefix, suffixes)) continue;
                    try {
                        URL fileUrl = file.toURI().toURL();
                        results.add(new ClasspathLoadableResource(filename, fileUrl));
                    } catch (Exception ignored) {
                        // Skip unreadable file
                    }
                }

            } else if ("jar".equals(protocol)) {
                // JAR-packaged deployment (fat JAR)
                String spec = baseUrl.getFile();
                int bangSlash = spec.indexOf("!/");
                if (bangSlash < 0) return;
                String jarFilePath = spec.substring(0, bangSlash).replaceFirst("^file:", "");
                String jarEntry = spec.substring(bangSlash + 2); // e.g., "db/migration"
                if (!jarEntry.endsWith("/")) jarEntry = jarEntry + "/";

                try (JarFile jar = new JarFile(java.net.URLDecoder.decode(jarFilePath, "UTF-8"))) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String entryName = entry.getName(); // e.g., "db/migration/V1__foo.sql"
                        if (!entryName.startsWith(jarEntry) || entry.isDirectory()) continue;
                        String relative =
                                entryName.substring(jarEntry.length()); // e.g., "V1__foo.sql"
                        if (relative.contains("/")) continue; // sub-directory entry — skip
                        if (!matchesPrefixAndSuffixes(relative, prefix, suffixes)) continue;
                        URL resourceUrl = cl.getResource(entryName);
                        if (resourceUrl != null) {
                            results.add(new ClasspathLoadableResource(relative, resourceUrl));
                        }
                    }
                }
            }
            // Other protocols (jboss-vfs, etc.) are not supported — Flyway will simply find
            // no migrations from those URLs, which is acceptable for test environments.
        }

        private static boolean matchesPrefixAndSuffixes(
                String name, String prefix, String[] suffixes) {
            if (prefix != null && !name.startsWith(prefix)) return false;
            if (suffixes == null || suffixes.length == 0) return true;
            for (String suffix : suffixes) {
                if (suffix != null && name.endsWith(suffix)) return true;
            }
            return false;
        }

        private static ClassLoader contextClassLoader() {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return cl != null ? cl : FlywayRootMigrationsCustomizer.class.getClassLoader();
        }
    }

    /**
     * A minimal {@link LoadableResource} implementation backed by a classpath {@link URL}.
     *
     * <p>Satisfies Flyway's {@link LoadableResource} contract: provides filename, relative path,
     * absolute path, and a {@code read()} method backed by the URL's input stream.
     */
    static final class ClasspathLoadableResource extends LoadableResource {

        private final String filename;
        private final URL url;

        ClasspathLoadableResource(String filename, URL url) {
            this.filename = filename;
            this.url = url;
        }

        /** Returns the SQL file content as a {@link Reader} using UTF-8. */
        @Override
        public Reader read() {
            try {
                return new InputStreamReader(url.openStream(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Cannot read migration resource: " + filename, e);
            }
        }

        /** Returns the filename (e.g., {@code V1__initial_schema.sql}). */
        @Override
        public String getFilename() {
            return filename;
        }

        /**
         * Returns the relative path within the migration location. For root-level files this equals
         * the filename.
         */
        @Override
        public String getRelativePath() {
            return filename;
        }

        /** Returns the absolute classpath URL as a string. */
        @Override
        public String getAbsolutePath() {
            return url.toExternalForm();
        }

        /**
         * Returns the on-disk absolute path (for file:// URLs) or the URL string (for jar: URLs).
         * Flyway uses this for error messages and logging.
         */
        @Override
        public String getAbsolutePathOnDisk() {
            if ("file".equals(url.getProtocol())) {
                return new File(url.getFile()).getAbsolutePath();
            }
            return url.toExternalForm();
        }
    }
}
