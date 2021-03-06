package com.edgar.jdbc.codegen.gen;

import com.google.common.base.CaseFormat;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;

import com.edgar.jdbc.codegen.db.DBFetcher;
import com.edgar.jdbc.codegen.db.Table;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Created by Edgar on 2017/5/17.
 *
 * @author Edgar  Date 2017/5/17
 */
public class Generator {
  private static final Logger LOGGER = LoggerFactory.getLogger(Generator.class);

  private static final String COMMENT_START = "/* START Do not remove/edit this line. CodeGenerator "
                                         + "will preserve any code between start and end tags.*/";
  private static final String COMMENT_END = "/* END Do not remove/edit this line. CodeGenerator will "
                                       + "preserve any code between start and end tags.*/";

  private static final String IS_COMMENT_START = "/* START";
  private static final String IS_COMMENT_END = "/* END";

  private static final String tplFile = "tpl/domain.hbs";

  private final CodegenOptions options;

  private final String tpl;

  private final Handlebars handlebars = new Handlebars();

  private final String packageName;

  private final String srcFolderPath;

  public Generator(CodegenOptions options) {
    this.options = options;
    this.tpl = resolveFile(tplFile);
    this.packageName = options.getDomainPackage();
    this.srcFolderPath = options.getSrcFolderPath();
    handlebars.registerHelper("safestr", new Helper<String>() {
      @Override
      public Object apply(String str, Options options) throws IOException {
        return new Handlebars.SafeString(str);
      }
    });
    handlebars.registerHelper("lowUnderscoreToLowCamel", new Helper<String>() {
      @Override
      public Object apply(String str, Options options) throws IOException {
        return (CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, str));
      }
    });
  }

  private synchronized String readFromFileURL(URL url) {
    File resource;
    try {
      resource = new File(URLDecoder.decode(url.getPath(), "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
    boolean isDirectory = resource.isDirectory();
    if (isDirectory) {
      throw new RuntimeException(url + "is dir");
    }
    try {
      String data = new String(Files.readAllBytes(resource.toPath()));
      return data;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void generate() {
    List<Table> tables = new DBFetcher(options).fetchTablesFromDb();
    tables.forEach(t -> execute(t));
  }

  private void execute(Table table) {
    try {
      StringBuffer userSource = readUserSourceCode(table);
      Template template = handlebars.compileInline(tpl);
      String code = template.apply(ImmutableMap.of("table", table,
                                                   "package", packageName,
                                                   "userSource", userSource.toString()));
      createFile(table, code);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private String resolveFile(String fileName) {
    // First look for file with that name on disk
    File file = new File(fileName);
    // We need to synchronized here to avoid 2 different threads to copy the file to the cache
    // directory and so
    // corrupting the content.
    synchronized (this) {
      ClassLoader cl = getClassLoader();
      URL url = cl.getResource(fileName);
      if (url != null) {
        String prot = url.getProtocol();
        switch (prot) {
          case "file":
            return readFromFileURL(url);
          case "jar":
            return readFromJarURL(url);
          default:
            throw new IllegalStateException("Invalid url protocol: " + prot);
        }
      }
    }
    throw new IllegalStateException("Invalid fileName: " + fileName);
  }

  private void createPackage(String rootFolderPath, String packageName) throws Exception {
    String path = "";
    if (!Strings.isNullOrEmpty(packageName)) {
      path = CharMatcher.anyOf(".").replaceFrom(packageName, "/");
      if (!Strings.isNullOrEmpty(rootFolderPath)) {
        path = rootFolderPath + "/" + path;
      }
      LOGGER.info("Generated code will be in folder:{}", path);
      File file = new File(path);
      if (!file.exists()) {
        file.mkdirs();
        LOGGER.info("Package structure created:" + path);
      } else {
        LOGGER.info("Package structure:{} exists.", path);
      }
    }
  }

  private void createFile(Table table, String code) throws Exception {
    createPackage(srcFolderPath, packageName);
    String fileName = this.getSourceFileName(table);
    File file = new File(fileName);
    FileWriter writer = new FileWriter(file);
    writer.write(code);
    writer.close();
    LOGGER.info("Class File created:" + file.getPath());
  }

  private String getSourceFileName(Table table) {
    String path = "";
    if (!Strings.isNullOrEmpty(packageName)) {
      path = CharMatcher.anyOf(".").replaceFrom(this.packageName, "/") + "/";
    }
    if (!Strings.isNullOrEmpty(this.srcFolderPath)) {
      path = this.srcFolderPath + "/" + path;
    }

    String fileName = path + table.getUpperCamelName() + ".java";
    return fileName;
  }

  private StringBuffer readUserSourceCode(Table table) {
    StringBuffer userSourceBuf = new StringBuffer();
    String fileName = this.getSourceFileName(table);
    File file = new File(fileName);
    if (!file.exists()) {
      userSourceBuf.append(COMMENT_START)
              .append("\n\t")
              .append(COMMENT_END);
      return userSourceBuf;
    }

    LOGGER.debug("File:{} exists, appending to existing file...", file.getPath());

    try {
      LOGGER.debug("Reading file :{}", file.getName());
      String contents =
              com.google.common.io.Files.asByteSource(file).asCharSource(Charset.defaultCharset())
                      .read();

      int startIndex = contents.indexOf(IS_COMMENT_START);
      int endIndex = contents.indexOf(IS_COMMENT_END);
      LOGGER.debug("Start index:{} End index:{}", startIndex, endIndex);
      if (startIndex != -1 && endIndex != -1) {
        userSourceBuf.append(contents.substring(startIndex, endIndex));
        userSourceBuf.append(COMMENT_END + "\n\n");
      }
      // save the imports
      List<String> lines = com.google.common.io.Files.readLines(file, Charset.defaultCharset());
      for (String line : lines) {
        if (line.startsWith("import")) {
          String[] tokens = Iterables.toArray(Splitter.on(" ").split(line), String.class);
          if (tokens.length > 2) {
            String iClass = tokens[1] + " " + tokens[2].substring(0, tokens[2].length() - 1);
            LOGGER.debug("iClass:{}", iClass);
            if (!table.getImports().contains(iClass)) {
              table.addImport(iClass);
            }
          } else {
            String iClass = tokens[1].substring(0, tokens[1].length() - 1);
            LOGGER.debug("iClass:{}", iClass);
            if (!table.getImports().contains(iClass)) {
              table.addImport(iClass);
            }
          }
        }
      }

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    if (userSourceBuf.length() == 0) {
      userSourceBuf.append(COMMENT_START)
              .append("\n\t")
              .append(COMMENT_END);
    }
    return userSourceBuf;

  }

  private ClassLoader getClassLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = getClass().getClassLoader();
    }
    return cl;
  }

  private String readFromJarURL(URL url) {
    try {
      JarURLConnection jarURLConnection = (JarURLConnection) url.openConnection();
      JarFile jarFile = jarURLConnection.getJarFile();
      // 遍历Jar包
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry jarEntry = entries.nextElement();
        String fileName = jarEntry.getName();
        if (fileName.equals(tplFile)) {
          return new String(ByteStreams.toByteArray(jarFile.getInputStream(jarEntry)));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return null;
  }
}
