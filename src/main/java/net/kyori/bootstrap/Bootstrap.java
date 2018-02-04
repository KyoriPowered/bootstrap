/*
 * This file is part of bootstrap, licensed under the MIT License.
 *
 * Copyright (c) 2018 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.bootstrap;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public final class Bootstrap {
  /**
   * The default maximum depth value.
   */
  private static final int DEFAULT_MAX_DEPTH = 255;
  /**
   * A path matcher that matches JAR files.
   */
  private static final PathMatcher JAR_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.jar");
  /**
   * The name of the method that will be invoked on the target class.
   */
  private static final String BOOT_METHOD_NAME = "boot";
  /**
   * A set of paths to search for modules in.
   */
  private final Set<Path> paths = new HashSet<>();
  /**
   * The module containing the class to boot.
   */
  private final String moduleName;
  /**
   * The class to boot.
   */
  private final String className;
  /**
   * The application arguments.
   */
  private final String[] args;

  /**
   * Boot.
   *
   * @param consumer a consumer used to configure the bootstrap
   * @param moduleName the target module
   * @param className the target class
   * @param args the arguments
   * @throws BootstrapException if an exception is encountered while bootstrapping
   */
  public static void boot(final Consumer consumer, final String moduleName, final String className, final String[] args) throws BootstrapException {
    requireNonNull(consumer, "consumer");
    requireNonNull(moduleName, "module name");
    requireNonNull(className, "class name");
    requireNonNull(args, "args");

    final Bootstrap bootstrap = new Bootstrap(moduleName, className, args);
    try {
      consumer.accept(bootstrap);
      bootstrap.boot();
    } catch(final ClassNotFoundException | IllegalAccessException | InvocationTargetException | IOException | NoSuchMethodException e) {
      throw new BootstrapException("Encountered an exception while bootstrapping", e);
    }
  }

  private Bootstrap(final String moduleName, final String className, final String[] args) {
    this.moduleName = moduleName;
    this.className = className;
    this.args = args;
  }

  /**
   * Search for directories containing JARs that modules can be loaded from.
   *
   * @param path the path to search in
   * @throws IOException if an exception is encountered while walking the path
   */
  public void search(final Path path) throws IOException {
    requireNonNull(path, "path");
    this.search(path, Set.of());
  }

  /**
   * Search for directories containing JARs that modules can be loaded from.
   *
   * @param path the path to search in
   * @param options the file visit options
   * @throws IOException if an exception is encountered while walking the path
   */
  public void search(final Path path, final Set<FileVisitOption> options) throws IOException {
    requireNonNull(path, "path");
    requireNonNull(options, "options");
    this.search(path, options, DEFAULT_MAX_DEPTH);
  }

  /**
   * Search for directories containing JARs that modules can be loaded from.
   *
   * @param path the path to search in
   * @param maxDepth the maximum depth to search
   * @throws IOException if an exception is encountered while walking the path
   */
  public void search(final Path path, final int maxDepth) throws IOException {
    requireNonNull(path, "path");
    this.search(path, Set.of(), maxDepth);
  }

  /**
   * Search for directories containing JARs that modules can be loaded from.
   *
   * @param path the path to search in
   * @param options the file visit options
   * @param maxDepth the maximum depth to search
   * @throws IOException if an exception is encountered while walking the path
   */
  public void search(final Path path, final Set<FileVisitOption> options, final int maxDepth) throws IOException {
    requireNonNull(path, "path");
    requireNonNull(options, "options");
    Files.walkFileTree(path, options, maxDepth, new FileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
        if(JAR_MATCHER.matches(file)) {
          Bootstrap.this.paths.add(file.getParent());
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(final Path file, final IOException exception) {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) {
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private void boot() throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    final ModuleLayer layer = Modules.createLayer(this.paths);

    final Method method = layer.findLoader(this.moduleName)
      .loadClass(this.className)
      .getDeclaredMethod(BOOT_METHOD_NAME, ModuleLayer.class, String[].class);
    method.setAccessible(true);
    method.invoke(null, layer, this.args);
  }

  /**
   * A consumer that is used to configure a bootstrap.
   */
  public interface Consumer {
    /**
     * Configure a bootstrap.
     *
     * @param bootstrap the bootstrap
     * @throws IOException if an exception is encountered while configuring the bootstrap
     */
    void accept(final Bootstrap bootstrap) throws IOException;
  }
}
