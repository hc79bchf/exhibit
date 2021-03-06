/*
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
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
package com.cloudera.exhibit.etl;

import com.cloudera.exhibit.core.Exhibit;
import com.cloudera.exhibit.core.ExhibitDescriptor;
import com.cloudera.exhibit.etl.config.BuildConfig;
import com.cloudera.exhibit.etl.config.ComputeConfig;
import com.cloudera.exhibit.etl.config.ConfigHelper;
import com.cloudera.exhibit.etl.config.OutputConfig;
import com.cloudera.exhibit.etl.config.SourceConfig;
import com.cloudera.exhibit.etl.fn.ExCombiner;
import com.cloudera.exhibit.etl.fn.FilterOutFn;
import com.cloudera.exhibit.etl.fn.KeyIndexFn;
import com.cloudera.exhibit.etl.fn.MergeRowsFn;
import com.cloudera.exhibit.etl.fn.SchemaMapFn;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.crunch.GroupingOptions;
import org.apache.crunch.PCollection;
import org.apache.crunch.PTable;
import org.apache.crunch.Pair;
import org.apache.crunch.Pipeline;
import org.apache.crunch.PipelineResult;
import org.apache.crunch.Target;
import org.apache.crunch.impl.mr.MRPipeline;
import org.apache.crunch.io.To;
import org.apache.crunch.io.parquet.AvroParquetFileTarget;
import org.apache.crunch.lib.join.JoinUtils;
import org.apache.crunch.types.PTableType;
import org.apache.crunch.types.PType;
import org.apache.crunch.types.avro.AvroType;
import org.apache.crunch.types.avro.Avros;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.kitesdk.data.Dataset;
import org.kitesdk.data.DatasetDescriptor;
import org.kitesdk.data.Datasets;
import org.kitesdk.data.Formats;
import org.kitesdk.data.crunch.CrunchDatasets;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

import static com.cloudera.exhibit.etl.SchemaUtil.unionKeySchema;
import static com.cloudera.exhibit.etl.SchemaUtil.unionValueSchema;

public class ExhibitTool extends Configured implements Tool {
  @Override
  public int run(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: (build|compute|parse) <config.yml>");
      return -1;
    }
    if ("build".equalsIgnoreCase(args[0])) {
      return build(args[1]);
    } else if ("compute".equalsIgnoreCase(args[0])) {
      return compute(args[1]);
    } else if ("parse".equalsIgnoreCase(args[0])) {
      return parse(args[1]);
    } else {
      System.err.println("Usage: (build|compute) <config.yml>");
      return -1;
    }
  }

  int parse(String arg) throws Exception {
    ComputeConfig config = ConfigHelper.parseComputeConfig(arg);
    System.out.println("Config parsed successfully");
    return 0;
  }

  int compute(String arg) throws Exception {
    ComputeConfig config = ConfigHelper.parseComputeConfig(arg);
    Pipeline p = new MRPipeline(ExhibitTool.class, getConf());
    Dataset<GenericRecord> data = Datasets.load(config.uri);
    PCollection<GenericRecord> input = p.read(CrunchDatasets.asSource(data));

    // Step one: generate additional tempTables, if any.
    RecordToExhibit rte = new RecordToExhibit(config.tempTables);
    ExhibitDescriptor descriptor = rte.getDescriptor(input.getPType());
    PCollection<Exhibit> exhibits = rte.apply(input);

    // Step two: determine the key and value schemas from the outputTables.
    List<OutputGen> outputGens = Lists.newArrayList();
    Set<Schema> keySchemas = Sets.newHashSet();
    List<List<SchemaProvider>> providerLists = Lists.newArrayList();
    Set<Schema> interValueSchemas = Sets.newHashSet();
    List<Schema> outputSchemas = Lists.newArrayList();
    for (int i = 0; i < config.outputTables.size(); i++) {
      OutputConfig output = config.outputTables.get(i);
      OutputGen gen = new OutputGen(i, output, descriptor);
      Schema keySchema = gen.getKeySchema();
      List<SchemaProvider> providers = gen.getSchemaProviders();

      List<Schema.Field> outputFields = Lists.newArrayList();
      for (Schema.Field sf : keySchema.getFields()) {
        outputFields.add(new Schema.Field(sf.name(), sf.schema(), sf.doc(), sf.defaultValue()));
      }
      for (SchemaProvider sp : providers) {
        Schema s = sp.get(1); // output
        for (Schema.Field sf : s.getFields()) {
          outputFields.add(new Schema.Field(sf.name(), sf.schema(), sf.doc(), sf.defaultValue()));
        }
      }
      Schema outputSchema = Schema.createRecord("ExOutput" + i, "", "exhibit", false);
      outputSchema.setFields(outputFields);
      System.out.println("Output Schema " + i + ": " + outputSchema.toString(true));

      keySchemas.add(keySchema);
      interValueSchemas.addAll(Lists.transform(providers, new Function<SchemaProvider, Schema>() {
                @Nullable
                @Override
                public Schema apply(SchemaProvider schemaProvider) {
                  return schemaProvider.get(0); // intermediate
                }
              }));
      outputGens.add(gen);
      outputSchemas.add(outputSchema);
      providerLists.add(providers);
    }

    Schema keySchema = unionKeySchema("ExhibitKey", Lists.newArrayList(keySchemas));
    Schema interValueSchema = unionValueSchema("ExhibitInterValue", Lists.newArrayList(interValueSchemas));
    SchemaProvider provider = new SchemaProvider(ImmutableList.of(keySchema, interValueSchema));
    AvroType<GenericData.Record> keyType = Avros.generics(keySchema);
    AvroType<GenericData.Record> interValueType = Avros.generics(interValueSchema);
    PTableType<Pair<GenericData.Record, Integer>, Pair<Integer, GenericData.Record>> ptt = Avros.tableOf(
        Avros.pairs(keyType, Avros.ints()),
        Avros.pairs(Avros.ints(), interValueType));
    PTable<Pair<GenericData.Record, Integer>, Pair<Integer, GenericData.Record>> mapside = null;
    for (int i = 0; i < outputGens.size(); i++) {
      PTable<GenericData.Record, Pair<Integer, GenericData.Record>> out = outputGens.get(i).apply(exhibits);
      PTable<Pair<GenericData.Record, Integer>, Pair<Integer, GenericData.Record>> m = out.parallelDo(
          new SchemaMapFn(i, provider), ptt);
      mapside = (mapside == null) ? m : mapside.union(m);
    }

    GroupingOptions opts = GroupingOptions.builder()
        .numReducers(config.parallelism)
        .partitionerClass(JoinUtils.AvroIndexedRecordPartitioner.class)
        .groupingComparatorClass(JoinUtils.AvroPairGroupingComparator.class)
        .build();
    Schema outputUnionSchema = unionValueSchema("ExOutputUnion", outputSchemas);
    PType<GenericData.Record> outputUnion = Avros.generics(outputUnionSchema);
    PTable<Integer, GenericData.Record> reduced = mapside.groupByKey(opts)
        .combineValues(new ExCombiner(provider, keyType, interValueType, config.outputTables, providerLists))
        .parallelDo("merge", new MergeRowsFn(config.outputTables, providerLists, outputUnionSchema),
            Avros.tableOf(Avros.ints(), outputUnion));

    for (int i = 0; i < config.outputTables.size(); i++) {
      OutputConfig output = config.outputTables.get(i);
      AvroType<GenericData.Record> outType = Avros.generics(outputSchemas.get(i));
      PCollection<GenericData.Record> out = reduced.parallelDo(new FilterOutFn(i), outType);
      if (config.local) {
        out.write(To.textFile(output.path), output.writeMode);
      } else {
        DatasetDescriptor dd = new DatasetDescriptor.Builder()
                .schema(outType.getSchema())
                .format(Formats.PARQUET)
                .location(output.path)
                .build();
        if (Datasets.exists(output.uri) && output.writeMode == Target.WriteMode.OVERWRITE) {
          Datasets.delete(output.uri);
        }
        Datasets.create(output.uri, dd);
        out.write(new AvroParquetFileTarget(output.path), output.writeMode);
      }
    }
    PipelineResult res = p.done();
    return res.succeeded() ? 0 : 1;
  }



  int build(String arg) throws Exception {
    BuildConfig config = ConfigHelper.parseBuildConfig(arg);
    Pipeline p = new MRPipeline(ExhibitTool.class, getConf());
    List<PCollection<GenericRecord>> pcols = Lists.newArrayList();
    Set<Schema> schemas = Sets.newHashSet();
    for (SourceConfig src : config.sources) {
      Dataset<GenericRecord> data = Datasets.load(src.uri);
      PCollection<GenericRecord> pcol = p.read(CrunchDatasets.asSource(data));
      pcols.add(pcol);
      Schema schema = ((AvroType) pcol.getPType()).getSchema();
      src.setSchema(schema);
      schemas.add(schema);
    }

    // Hack to union the various schemas that will get processed together.
    Schema wrapper = unionValueSchema("ExhibitWrapper", Lists.newArrayList(schemas));
    AvroType<GenericData.Record> valueType = Avros.generics(wrapper);

    AvroType<Pair<Integer, GenericData.Record>> ssType = Avros.pairs(Avros.ints(), valueType);
    PType<Object> keyType = (PType<Object>) config.keyType.getPType();
    PTableType<Object, Pair<Integer, GenericData.Record>> tableType = Avros.tableOf(keyType, ssType);
    PTable<Object, Pair<Integer, GenericData.Record>> union = null;
    for (int i = 0; i < config.sources.size(); i++) {
      SourceConfig src = config.sources.get(i);
      PCollection<GenericRecord> in = pcols.get(i);
      KeyIndexFn<GenericRecord> keyFn = new KeyIndexFn<GenericRecord>(valueType, src.keyFields, src.invalidKeys, i);
      PTable<Object, Pair<Integer, GenericData.Record>> keyed = in.parallelDo("src " + i, keyFn, tableType);
      if (union == null) {
        union = keyed;
      } else {
        union = union.union(keyed);
      }
    }
    MergeSchema ms = new MergeSchema(config.name, config.keyField, config.keyType.getSchema(), config.sources,
        config.parallelism);
    PCollection<GenericData.Record> output = ms.apply(union);
    DatasetDescriptor dd = new DatasetDescriptor.Builder()
        .schema(((AvroType) output.getPType()).getSchema())
        .format(Formats.PARQUET)
        .build();
    Dataset<GenericRecord> outputDataset = Datasets.create(config.uri, dd);
    output.write(CrunchDatasets.asTarget(outputDataset), config.writeMode);
    PipelineResult res = p.done();
    return res.succeeded() ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    int rc = ToolRunner.run(new Configuration(), new ExhibitTool(), args);
    System.exit(rc);
  }
}
