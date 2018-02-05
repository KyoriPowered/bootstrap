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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import static java.util.Objects.requireNonNull;

public final class Bootstrap {
  /**
   * The name of the configuration file.
   */
  private static final String CONFIGURATION_FILE_NAME = "bootstrap.xml";
  /**
   * The name of the element that contains paths we should search in.
   */
  private static final String PATH_ELEMENT_NAME = "path";
  /**
   * The name of the optional attribute that contains the minimum depth a path should be searched.
   */
  private static final String PATH_MIN_DEPTH_ATTRIBUTE_NAME = "min-depth";
  /**
   * The name of the optional attribute that contains the maximum depth a path should be searched.
   */
  private static final String PATH_MAX_DEPTH_ATTRIBUTE_NAME = "max-depth";
  /**
   * The name of the attribute that contains our target module.
   */
  private static final String MODULE_ATTRIBUTE_NAME = "module";
  /**
   * The name of the attribute that contains our target class.
   */
  private static final String CLASS_ATTRIBUTE_NAME = "class";
  /**
   * The default minimum depth value.
   */
  private static final int DEFAULT_MIN_DEPTH = 1;
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
   * Application entry point.
   *
   * @param args the arguments
   */
  public static void main(final String[] args) throws BootstrapException {
    final Element document;
    try {
      document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Paths.get(CONFIGURATION_FILE_NAME).toFile()).getDocumentElement();
    } catch(final IOException | ParserConfigurationException | SAXException e) {
      throw new BootstrapException("Encountered an exception while parsing bootstrap configuration file ()", e);
    }
    boot(
      bootstrap -> {
        final NodeList paths = document.getElementsByTagName(PATH_ELEMENT_NAME);
        for(int i = 0, length = paths.getLength(); i < length; i++) {
          final Node node = paths.item(i);
          if(node.getNodeType() == Node.ELEMENT_NODE) {
            final Element path = (Element) node;
            final int minDepth = path.hasAttribute(PATH_MIN_DEPTH_ATTRIBUTE_NAME) ? Integer.parseInt(path.getAttribute(PATH_MIN_DEPTH_ATTRIBUTE_NAME)) : DEFAULT_MIN_DEPTH;
            final int maxDepth = path.hasAttribute(PATH_MAX_DEPTH_ATTRIBUTE_NAME) ? Integer.parseInt(path.getAttribute(PATH_MAX_DEPTH_ATTRIBUTE_NAME)) : DEFAULT_MAX_DEPTH;
            bootstrap.search(Paths.get(path.getTextContent()), minDepth, maxDepth);
          }
        }
      },
      requireString(document.getAttribute(MODULE_ATTRIBUTE_NAME), MODULE_ATTRIBUTE_NAME),
      requireString(document.getAttribute(CLASS_ATTRIBUTE_NAME), CLASS_ATTRIBUTE_NAME),
      args
    );
  }

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
    this.search(path, options, DEFAULT_MIN_DEPTH, DEFAULT_MAX_DEPTH);
  }

  /**
   * Search for directories containing JARs that modules can be loaded from.
   *
   * @param path the path to search in
   * @param minDepth the minimum depth to search
   * @param maxDepth the maximum depth to search
   * @throws IOException if an exception is encountered while walking the path
   */
  public void search(final Path path, final int minDepth, final int maxDepth) throws IOException {
    requireNonNull(path, "path");
    this.search(path, Set.of(), minDepth, maxDepth);
  }

  /**
   * Search for directories containing JARs that modules can be loaded from.
   *
   * @param path the path to search in
   * @param options the file visit options
   * @param minDepth the minimum depth to search
   * @param maxDepth the maximum depth to search
   * @throws IOException if an exception is encountered while walking the path
   */
  public void search(final Path path, final Set<FileVisitOption> options, final int minDepth, final int maxDepth) throws IOException {
    requireNonNull(path, "path");
    requireNonNull(options, "options");
    Files.walkFileTree(path, options, maxDepth, new FileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
        if(this.countDepth(path, file.getParent()) >= minDepth) {
          if(JAR_MATCHER.matches(file)) {
            Bootstrap.this.paths.add(file.getParent());
          }
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

      private int countDepth(final Path start, final Path current) {
        return start.relativize(current).getNameCount();
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

  private static String requireString(final String string, final String message) {
    requireNonNull(string, message);
    if(string.isEmpty()) {
      throw new IllegalArgumentException(message);
    }
    return string;
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
