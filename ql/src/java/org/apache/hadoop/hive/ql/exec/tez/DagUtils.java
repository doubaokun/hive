/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.exec.tez;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.mr.ExecMapper;
import org.apache.hadoop.hive.ql.exec.mr.ExecReducer;
import org.apache.hadoop.hive.ql.io.BucketizedHiveInputFormat;
import org.apache.hadoop.hive.ql.io.HiveKey;
import org.apache.hadoop.hive.ql.io.HiveOutputFormatImpl;
import org.apache.hadoop.hive.ql.plan.BaseWork;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.ReduceWork;
import org.apache.hadoop.hive.shims.Hadoop20Shims.NullOutputCommitter;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputFormat;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.EdgeProperty.ConnectionPattern;
import org.apache.tez.dag.api.EdgeProperty.SourceType;
import org.apache.tez.dag.api.InputDescriptor;
import org.apache.tez.dag.api.OutputDescriptor;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.engine.lib.input.ShuffledMergedInput;
import org.apache.tez.engine.lib.output.OnFileSortedOutput;
import org.apache.tez.mapreduce.hadoop.InputSplitInfo;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.apache.tez.mapreduce.hadoop.MultiStageMRConfToTezTranslator;
import org.apache.tez.mapreduce.processor.map.MapProcessor;
import org.apache.tez.mapreduce.processor.reduce.ReduceProcessor;

/**
 * DagUtils. DagUtils is a collection of helper methods to convert
 * map and reduce work to tez vertices and edges. It handles configuration
 * objects, file localization and vertex/edge creation.
 */
public class DagUtils {

  /*
   * Creates the configuration object necessary to run a specific vertex from
   * map work. This includes input formats, input processor, etc.
=  */
  private static JobConf initializeVertexConf(JobConf baseConf, MapWork mapWork) {
    JobConf conf = new JobConf(baseConf);

    if (mapWork.getNumMapTasks() != null) {
      conf.setInt(MRJobConfig.NUM_MAPS, mapWork.getNumMapTasks().intValue());
    }

    if (mapWork.getMaxSplitSize() != null) {
      HiveConf.setLongVar(conf, HiveConf.ConfVars.MAPREDMAXSPLITSIZE,
          mapWork.getMaxSplitSize().longValue());
    }

    if (mapWork.getMinSplitSize() != null) {
      HiveConf.setLongVar(conf, HiveConf.ConfVars.MAPREDMINSPLITSIZE,
          mapWork.getMinSplitSize().longValue());
    }

    if (mapWork.getMinSplitSizePerNode() != null) {
      HiveConf.setLongVar(conf, HiveConf.ConfVars.MAPREDMINSPLITSIZEPERNODE,
          mapWork.getMinSplitSizePerNode().longValue());
    }

    if (mapWork.getMinSplitSizePerRack() != null) {
      HiveConf.setLongVar(conf, HiveConf.ConfVars.MAPREDMINSPLITSIZEPERRACK,
          mapWork.getMinSplitSizePerRack().longValue());
    }

    Utilities.setInputAttributes(conf, mapWork);

    String inpFormat = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEINPUTFORMAT);
    if ((inpFormat == null) || (!StringUtils.isNotBlank(inpFormat))) {
      inpFormat = ShimLoader.getHadoopShims().getInputFormatClassName();
    }

    if (mapWork.isUseBucketizedHiveInputFormat()) {
      inpFormat = BucketizedHiveInputFormat.class.getName();
    }

    conf.set(MRJobConfig.MAP_CLASS_ATTR, ExecMapper.class.getName());
    conf.set(MRJobConfig.INPUT_FORMAT_CLASS_ATTR, inpFormat);

    return conf;
  }

  /**
   * Given two vertices and their respective configuration objects createEdge
   * will create an Edge object that connects the two. Currently the edge will
   * always be a stable bi-partite edge.
   *
   * @param vConf JobConf of the first vertex
   * @param v The first vertex (source)
   * @param wConf JobConf of the second vertex
   * @param w The second vertex (sink)
   * @return
   */
  public static Edge createEdge(JobConf vConf, Vertex v, JobConf wConf, Vertex w) {

    // Tez needs to setup output subsequent input pairs correctly
    MultiStageMRConfToTezTranslator.translateVertexConfToTez(wConf, vConf);

    // all edges are of the same type right now
    EdgeProperty edgeProperty =
        new EdgeProperty(ConnectionPattern.BIPARTITE, SourceType.STABLE,
            new OutputDescriptor(OnFileSortedOutput.class.getName(), null),
            new InputDescriptor(ShuffledMergedInput.class.getName(), null));
    return new Edge(v, w, edgeProperty);
  }

  /*
   * Helper function to create Vertex from MapWork.
   */
  private static Vertex createVertex(JobConf conf, MapWork mapWork, int seqNo,
      LocalResource appJarLr, List<LocalResource> additionalLr, FileSystem fs,
      Path mrScratchDir, Context ctx) throws Exception {

    // map work can contain localwork, i.e: hashtables for map-side joins
    Path hashTableArchive = createHashTables(mapWork, conf);
    LocalResource localWorkLr = null;
    if (hashTableArchive != null) {
      localWorkLr = createLocalResource(fs,
          hashTableArchive, LocalResourceType.ARCHIVE,
          LocalResourceVisibility.APPLICATION);
    }

    // write out the operator plan
    Path planPath = Utilities.setMapWork(conf, mapWork, mrScratchDir.toUri().toString(), false);
    LocalResource planLr = createLocalResource(fs,
        planPath, LocalResourceType.FILE,
        LocalResourceVisibility.APPLICATION);

    // setup input paths and split info
    List<Path> inputPaths = Utilities.getInputPaths(conf, mapWork, mrScratchDir.toUri().toString(), ctx);
    Utilities.setInputPaths(conf, inputPaths);

    InputSplitInfo inputSplitInfo = MRHelpers.generateInputSplits(conf, mrScratchDir);
    MultiStageMRConfToTezTranslator.translateVertexConfToTez(conf, conf);

    // finally create the vertex
    Vertex map = null;
    if (inputSplitInfo.getNumTasks() != 0) {
      map = new Vertex("Map "+seqNo,
          new ProcessorDescriptor(MapProcessor.class.getName(),
              MRHelpers.createUserPayloadFromConf(conf)),
          inputSplitInfo.getNumTasks(), MRHelpers.getMapResource(conf));
      Map<String, String> environment = new HashMap<String, String>();
      MRHelpers.updateEnvironmentForMRTasks(conf, environment, true);
      map.setTaskEnvironment(environment);
      map.setJavaOpts(MRHelpers.getMapJavaOpts(conf));

      map.setTaskLocationsHint(inputSplitInfo.getTaskLocationHints());

      Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();
      if (localWorkLr != null) {
        localResources.put(hashTableArchive.getName(), localWorkLr);
      }
      localResources.put(appJarLr.getResource().getFile(), appJarLr);
      for (LocalResource lr: additionalLr) {
        localResources.put(lr.getResource().getFile(), lr);
      }
      localResources.put(planPath.getName(), planLr);

      MRHelpers.updateLocalResourcesForInputSplits(FileSystem.get(conf), inputSplitInfo,
          localResources);
      map.setTaskLocalResources(localResources);
    }
    return map;
  }

  /*
   * If the given MapWork has local work embedded we need to generate the corresponding
   * hash tables and localize them. These tables will be used by the map work to do
   * map-side joins.
   */
  private static Path createHashTables(MapWork mapWork, Configuration conf) {
    return null;
  }

  /*
   * Helper function to create JobConf for specific ReduceWork.
   */
  private static JobConf initializeVertexConf(JobConf baseConf, ReduceWork reduceWork) {
    JobConf conf = new JobConf(baseConf);

    conf.set(MRJobConfig.REDUCE_CLASS_ATTR, ExecReducer.class.getName());

    boolean useSpeculativeExecReducers = HiveConf.getBoolVar(conf,
        HiveConf.ConfVars.HIVESPECULATIVEEXECREDUCERS);
    HiveConf.setBoolVar(conf, HiveConf.ConfVars.HADOOPSPECULATIVEEXECREDUCERS,
        useSpeculativeExecReducers);

    // reducers should have been set at planning stage
    // job.setNumberOfReducers(rWork.getNumberOfReducers())
    conf.set(MRJobConfig.NUM_REDUCES, reduceWork.getNumReduceTasks().toString());

    return conf;
  }

  /*
   * Helper function to create Vertex for given ReduceWork.
   */
  private static Vertex createVertex(JobConf conf, ReduceWork reduceWork, int seqNo,
      LocalResource appJarLr, List<LocalResource> additionalLr, FileSystem fs,
      Path mrScratchDir, Context ctx) throws Exception {

    // write out the operator plan
    Path planPath = Utilities.setReduceWork(conf, reduceWork,
        mrScratchDir.getName(), false);
    LocalResource planLr = createLocalResource(fs, planPath,
        LocalResourceType.FILE, LocalResourceVisibility.APPLICATION);

    // create the vertex
    Vertex reducer = new Vertex("Reducer "+seqNo,
        new ProcessorDescriptor(ReduceProcessor.class.getName(),
            MRHelpers.createUserPayloadFromConf(conf)),
            reduceWork.getNumReduceTasks(), MRHelpers.getReduceResource(conf));

    Map<String, String> environment = new HashMap<String, String>();

    MRHelpers.updateEnvironmentForMRTasks(conf, environment, false);
    reducer.setTaskEnvironment(environment);

    reducer.setJavaOpts(MRHelpers.getReduceJavaOpts(conf));

    Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();
    localResources.put(appJarLr.getResource().getFile(), appJarLr);
    for (LocalResource lr: additionalLr) {
      localResources.put(lr.getResource().getFile(), lr);
    }
    localResources.put(planPath.getName(), planLr);
    reducer.setTaskLocalResources(localResources);

    return reducer;
  }

  /*
   * Helper method to create a yarn local resource.
   */
  private static LocalResource createLocalResource(FileSystem remoteFs, Path file,
      LocalResourceType type, LocalResourceVisibility visibility) {

    FileStatus fstat = null;
    try {
      fstat = remoteFs.getFileStatus(file);
    } catch (IOException e) {
      e.printStackTrace();
    }

    URL resourceURL = ConverterUtils.getYarnUrlFromPath(file);
    long resourceSize = fstat.getLen();
    long resourceModificationTime = fstat.getModificationTime();

    LocalResource lr = Records.newRecord(LocalResource.class);
    lr.setResource(resourceURL);
    lr.setType(type);
    lr.setSize(resourceSize);
    lr.setVisibility(visibility);
    lr.setTimestamp(resourceModificationTime);

    return lr;
  }

  /**
   * Localizes files, archives and jars the user has instructed us
   * to provide on the cluster as resources for execution.
   *
   * @param conf
   * @return List<LocalResource> local resources to add to execution
   */
  public static List<LocalResource> localizeTempFiles(Configuration conf) {
    List<LocalResource> tmpResources = new ArrayList<LocalResource>();

    String auxJars = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEAUXJARS);
    String addedJars = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEADDEDJARS);
    String addedFiles = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEADDEDFILES);
    String addedArchives = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEADDEDARCHIVES);

    // need to localize the additional jars and files

    return tmpResources;
  }

  /**
   * Creates a local resource representing the hive-exec jar. This resource will
   * be used to execute the plan on the cluster.
   */
  public static LocalResource createHiveExecLocalResource(Path mrScratchDir) {
    return null;
  }

  /**
   * Creates and initializes a JobConf object that can be used to execute
   * the DAG. The configuration object will contain configurations from mapred-site
   * overlaid with key/value pairs from the hiveConf object. Finally it will also
   * contain some hive specific configurations that do not change from DAG to DAG.
   *
   * @param hiveConf Current hiveConf for the execution
   * @return JobConf base configuration for job execution
   * @throws IOException
   */
  public static JobConf createConfiguration(HiveConf hiveConf) throws IOException {
    hiveConf.setBoolean("mapred.mapper.new-api", false);

    JobConf conf = (JobConf) MRHelpers.getBaseMRConfiguration();
    MRHelpers.doJobClientMagic(conf);

    for (Map.Entry<String, String> entry: hiveConf) {
      if (conf.get(entry.getKey()) == null) {
          conf.set(entry.getKey(), entry.getValue());
      }
    }

    conf.set("mapreduce.framework.name","yarn-tez");
    conf.set("mapreduce.job.output.committer.class", NullOutputCommitter.class.getName());

    conf.setBoolean(MRJobConfig.SETUP_CLEANUP_NEEDED, false);
    conf.setBoolean(MRJobConfig.TASK_CLEANUP_NEEDED, false);

    conf.setClass(MRJobConfig.OUTPUT_FORMAT_CLASS_ATTR, HiveOutputFormatImpl.class, OutputFormat.class);

    conf.set(MRJobConfig.MAP_CLASS_ATTR, ExecMapper.class.getName());

    conf.set(MRJobConfig.OUTPUT_KEY_CLASS, HiveKey.class.getName());
    conf.set(MRJobConfig.OUTPUT_VALUE_CLASS, BytesWritable.class.getName());

    conf.set(MRJobConfig.PARTITIONER_CLASS_ATTR, HiveConf.getVar(conf, HiveConf.ConfVars.HIVEPARTITIONER));

    return conf;
  }

  /**
   * Creates and initializes the JobConf object for a given BaseWork object.
   *
   * @param conf Any configurations in conf will be copied to the resulting new JobConf object.
   * @param work BaseWork will be used to populate the configuration object.
   * @return JobConf new configuration object
   */
  public static JobConf initializeVertexConf(JobConf conf, BaseWork work) {

    // simply dispatch the call to the right method for the actual (sub-) type of
    // BaseWork.
    if (work instanceof MapWork) {
      return initializeVertexConf(conf, (MapWork)work);
    } else if (work instanceof ReduceWork) {
      return initializeVertexConf(conf, (ReduceWork)work);
    } else {
      assert false;
      return null;
    }
  }

  /**
   * Create a vertex from a given work object.
   *
   * @param conf JobConf to be used to this execution unit
   * @param work The instance of BaseWork representing the actual work to be performed
   * by this vertex.
   * @param scratchDir HDFS scratch dir for this execution unit.
   * @param seqNo Unique number for this DAG. Used to name the vertex.
   * @param appJarLr Local resource for hive-exec.
   * @param additionalLr
   * @param fileSystem FS corresponding to scratchDir and LocalResources
   * @param ctx This query's context
   * @return Vertex
   */
  public static Vertex createVertex(JobConf conf, BaseWork work,
      Path scratchDir, int seqNo, LocalResource appJarLr, List<LocalResource> additionalLr,
      FileSystem fileSystem, Context ctx) throws Exception {

    // simply dispatch the call to the right method for the actual (sub-) type of
    // BaseWork.
    if (work instanceof MapWork) {
      return createVertex(conf, (MapWork) work, seqNo, appJarLr,
          additionalLr, fileSystem, scratchDir, ctx);
    } else if (work instanceof ReduceWork) {
      return createVertex(conf, (ReduceWork) work, seqNo, appJarLr,
          additionalLr, fileSystem, scratchDir, ctx);
    } else {
      assert false;
      return null;
    }
  }

  private DagUtils() {
    // don't instantiate
  }
}