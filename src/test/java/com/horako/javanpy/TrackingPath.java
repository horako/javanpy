package com.horako.javanpy;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/** Provides an instrumented stream to the path overload through the standard NIO provider API. */
final class TrackingPath {
    private TrackingPath() {
    }

    static Path forInput(InputStream input) {
        FileSystem system = new TestFileSystem(new TestProvider(input));
        return (Path) Proxy.newProxyInstance(Path.class.getClassLoader(), new Class<?>[]{Path.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getFileSystem")) {
                        return system;
                    }
                    return method.invoke(Path.of("tracked.npy"), args);
                });
    }

    private static final class TestFileSystem extends FileSystem {
        private final FileSystemProvider provider;

        private TestFileSystem(FileSystemProvider provider) {
            this.provider = provider;
        }

        @Override public FileSystemProvider provider() { return provider; }
        @Override public void close() { }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isReadOnly() { return true; }
        @Override public String getSeparator() { return "/"; }
        @Override public Iterable<Path> getRootDirectories() { throw new UnsupportedOperationException(); }
        @Override public Iterable<FileStore> getFileStores() { throw new UnsupportedOperationException(); }
        @Override public Set<String> supportedFileAttributeViews() { throw new UnsupportedOperationException(); }
        @Override public Path getPath(String first, String... more) { throw new UnsupportedOperationException(); }
        @Override public PathMatcher getPathMatcher(String syntaxAndPattern) { throw new UnsupportedOperationException(); }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() { throw new UnsupportedOperationException(); }
        @Override public WatchService newWatchService() { throw new UnsupportedOperationException(); }
    }

    private static final class TestProvider extends FileSystemProvider {
        private final InputStream input;

        private TestProvider(InputStream input) {
            this.input = input;
        }

        @Override public InputStream newInputStream(Path path, OpenOption... options) { return input; }
        @Override public String getScheme() { return "tracking"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { throw new UnsupportedOperationException(); }
        @Override public Path getPath(URI uri) { throw new UnsupportedOperationException(); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                FileAttribute<?>... attrs) { throw new UnsupportedOperationException(); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }
        @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) { throw new UnsupportedOperationException(); }
        @Override public void delete(Path path) { throw new UnsupportedOperationException(); }
        @Override public void copy(Path source, Path target, CopyOption... options) { throw new UnsupportedOperationException(); }
        @Override public void move(Path source, Path target, CopyOption... options) { throw new UnsupportedOperationException(); }
        @Override public boolean isSameFile(Path path, Path path2) { throw new UnsupportedOperationException(); }
        @Override public boolean isHidden(Path path) { throw new UnsupportedOperationException(); }
        @Override public FileStore getFileStore(Path path) { throw new UnsupportedOperationException(); }
        @Override public void checkAccess(Path path, AccessMode... modes) { throw new UnsupportedOperationException(); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
        @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
        @Override public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }
}
