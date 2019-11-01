//    Copyright 2019 Google Inc.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        https://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package com.google.fhir.examples;

import com.google.fhir.r4.core.Bundle;
import com.google.fhir.stu3.JsonFormat.Parser;
import com.google.fhir.stu3.ResourceUtils;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.TFRecordIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.MetricNameFilter;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SimpleFunction;

/**
 * This utility reads bundles generated by Synthea, formats the references in ways expected by the
 * downstream TensorFlow pipeline, and then emits them in TFRecord format. It is implemented as an
 * Apache Beam pipeline.
 */
public class SyntheaToTFRecordsMain {

  static class ReadJsonFilesFn extends DoFn<FileIO.ReadableFile, String> {
    private final Counter numInputFiles = Metrics.counter(ReadJsonFilesFn.class, "numInputFiles");

    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      numInputFiles.inc();
      c.output(c.element().readFullyAsUTF8String());
    }
  }

  static class ParseBundleFn extends DoFn<String, Bundle> {
    @ProcessElement
    public void processElement(ProcessContext c) throws Exception {
      Parser fhirParser = com.google.fhir.stu3.JsonFormat.getParser();

      // Parse the input bundle.
      Bundle.Builder builder = Bundle.newBuilder();
      fhirParser.merge(c.element(), builder);

      // Some FHIR implementations use absolute urls for references, such as urn:uuid:<identifier>,
      // we'd like to resolve them to for example Patient/<identifier> instead. Here we do it in an
      // ad-hoc way, creating a map of full url to relative reference, and then apply that mapping
      // directly to the input string. It's fragile and slow, but enough for an example application.
      // For more details on resolving references in bundles, see
      // https://www.hl7.org/fhir/bundle.html#references
      Bundle bundle = ResourceUtils.resolveBundleReferences(builder.build());
      c.output(bundle);
    }
  }

  static class BundleToByteArrayFn extends SimpleFunction<Bundle, byte[]> {
    @Override
    public byte[] apply(Bundle input) {
      return input.toByteArray();
    }
  }

  /** Command-line options. */
  public interface Options extends PipelineOptions {
    /** This option specifies the inputs to read from. */
    @Description("Path to input bundles")
    @Required
    String getInput();

    void setInput(String value);

    /** This option specifies the output location. */
    @Description("Output path")
    @Required
    String getOutput();

    void setOutput(String value);

    /** This option specifies the number of output shards. */
    @Description("Number of shards in the output")
    @Default.Integer(10)
    Integer getNumOutputShards();

    void setNumOutputShards(Integer value);
  }

  public static void main(String[] args) {
    // This Apache Beam pipeline runs with the DirectRunner by default.
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    Pipeline p = Pipeline.create(options);

    // Construct the pipeline
    p.apply("Define input files", FileIO.match().filepattern(options.getInput()))
        .apply("Open input files", FileIO.readMatches())
        .apply("Read input files", ParDo.of(new ReadJsonFilesFn()))
        .apply("Parse into bundles", ParDo.of(new ParseBundleFn()))
        .apply("Convert to byte array", MapElements.via(new BundleToByteArrayFn()))
        .apply(
            "Write as TFRecord",
            TFRecordIO.write()
                .to(options.getOutput())
                .withSuffix(".tfrecords")
                .withNumShards(options.getNumOutputShards()));

    // Run and print counters.
    PipelineResult result = p.run();
    result.waitUntilFinish();
    MetricResults metrics = result.metrics();
    MetricQueryResults metricResults =
        metrics.queryMetrics(
            MetricsFilter.builder()
                .addNameFilter(MetricNameFilter.inNamespace(ReadJsonFilesFn.class))
                .build());
    for (MetricResult<Long> c : metricResults.getCounters()) {
      System.out.println(c);
    }
  }
}
