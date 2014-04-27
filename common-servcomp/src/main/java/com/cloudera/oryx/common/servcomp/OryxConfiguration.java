/*
 * Copyright (c) 2013, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.common.servcomp;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.typesafe.config.Config;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.oryx.common.settings.ConfigUtils;

/**
 * {@link Configuration} subclass which 'patches' the Hadoop default with a few adjustments to
 * out-of-the-box settings.
 * 
 * @author Sean Owen
 */
public final class OryxConfiguration {

  private static final String HADOOP_CONF_DIR_KEY = "HADOOP_CONF_DIR";
  private static final String DEFAULT_HADOOP_CONF_DIR = "/etc/hadoop/conf";

  private static final Logger log = LoggerFactory.getLogger(OryxConfiguration.class);

  public static Configuration get() {
    Configuration conf = new Configuration();
    configure(conf);
    return conf;
  }

  public static Configuration get(Configuration conf) {
    Configuration confCopy = new Configuration(conf);
    configure(confCopy);
    return confCopy;
  }

  private OryxConfiguration() {
  }

  private static void configure(Configuration conf) {
    Config config = ConfigUtils.getDefaultConfig();
    boolean localComputation;
    if (config.hasPath("model.local")) {
      log.warn("model.local is deprecated; use model.local-computation");
      localComputation = config.getBoolean("model.local");
    } else {
      localComputation = config.getBoolean("model.local-computation");
    }
    if (!localComputation) {
      Path hadoopConfDir = findHadoopConfDir();
      addResource(hadoopConfDir, "core-site.xml", conf);
      addResource(hadoopConfDir, "hdfs-site.xml", conf);
      addResource(hadoopConfDir, "mapred-site.xml", conf);
      addResource(hadoopConfDir, "yarn-site.xml", conf);

      String fsDefaultFS = conf.get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY);
      if (fsDefaultFS == null || fsDefaultFS.equals(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_DEFAULT)) {
        // Standard config generated by Hadoop 2.0.x seemed to set fs.default.name instead of fs.defaultFS?
        conf.set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, conf.get("fs.default.name"));
      }

      fixLzoCodecIssue(conf);
    }
  }

  private static void addResource(Path hadoopConfDir, String fileName, Configuration conf) {
    Path file = hadoopConfDir.resolve(fileName);
    if (!Files.exists(file)) {
      log.info("Hadoop config file not found: {}", file);
      return;
    }
    try {
      conf.addResource(file.toUri().toURL());
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path findHadoopConfDir() {
    String hadoopConfPath = System.getenv(HADOOP_CONF_DIR_KEY);
    if (hadoopConfPath == null) {
      hadoopConfPath = DEFAULT_HADOOP_CONF_DIR;
    }
    Path hadoopConfDir = Paths.get(hadoopConfPath);
    Preconditions.checkState(Files.isDirectory(hadoopConfDir), "Not a directory: %s", hadoopConfDir);
    return hadoopConfDir;
  }

  /**
   * Removes {@code LzoCodec} and {@code LzopCodec} from key {@code io.compression.codecs}.
   * Implementations aren't shipped with Hadoop, but are in some cases instantiated anyway even when unused.
   * So, try to erase them.
   */
  private static void fixLzoCodecIssue(Configuration conf) {
    String codecsProperty = conf.get("io.compression.codecs");
    if (codecsProperty != null && codecsProperty.contains(".lzo.Lzo")) {
      Collection<String> codecs = new ArrayList<>();
      for (String codec : Splitter.on(',').split(codecsProperty)) {
        if (!codec.contains(".lzo.Lzo")) {
          codecs.add(codec);
        }
      }
      conf.set("io.compression.codecs", Joiner.on(',').join(codecs));
    }
  }

}
