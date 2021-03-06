/*
 * The MIT License
 *
 * Copyright (c) 2013 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package picard.illumina;

import htsjdk.samtools.SAMRecordQueryNameComparator;
import htsjdk.samtools.SAMUtils;
import htsjdk.samtools.fastq.BasicFastqWriter;
import htsjdk.samtools.fastq.FastqReader;
import htsjdk.samtools.fastq.FastqRecord;
import htsjdk.samtools.fastq.FastqWriter;
import htsjdk.samtools.fastq.FastqWriterFactory;
import htsjdk.samtools.util.CollectionUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.samtools.util.StringUtil;
import picard.PicardException;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.Usage;
import picard.fastq.Casava18ReadNameEncoder;
import picard.fastq.IlluminaReadNameEncoder;
import picard.fastq.ReadNameEncoder;
import picard.illumina.parser.ClusterData;
import picard.illumina.parser.ReadData;
import picard.illumina.parser.ReadStructure;
import picard.illumina.parser.readers.BclQualityEvaluationStrategy;
import picard.util.IlluminaUtil;
import picard.util.TabbedTextFileWithHeaderParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IlluminaBasecallsToFastq extends CommandLineProgram {
    // The following attributes define the command-line arguments
    @Usage
    public String USAGE =
            getStandardUsagePreamble() + "Generate fastq file(s) from data in an Illumina basecalls output directory.\n" +
            "Separate fastq file(s) are created for each template read, and for each barcode read, in the basecalls.\n" +
            "Template fastqs have extensions like .<number>.fastq, where <number> is the number of the template read,\n" +
            "starting with 1.  Barcode fastqs have extensions like .barcode_<number>.fastq, where <number> is the number\n" +
            "of the barcode read, starting with 1.";

    @Option(doc = "The basecalls directory. ", shortName = "B")
    public File BASECALLS_DIR;

    @Option(doc = "Lane number. ", shortName = StandardOptionDefinitions.LANE_SHORT_NAME)
    public Integer LANE;

    @Option(doc = "The prefix for output fastqs.  Extensions as described above are appended.  Use this option for " +
            "a non-barcoded run, or for a barcoded run in which it is not desired to demultiplex reads into separate " +
            "files by barcode.",
            shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME,
            mutex = {"MULTIPLEX_PARAMS"})
    public File OUTPUT_PREFIX;

    @Option(doc = "The barcode of the run.  Prefixed to read names.", optional = false)
    public String RUN_BARCODE;

    @Option(doc = "The name of the machine on which the run was sequenced; required if emitting Casava1.8-style read name headers", optional = true)
    public String MACHINE_NAME;
    
    @Option(doc = "The barcode of the flowcell that was sequenced; required if emitting Casava1.8-style read name headers", optional = true)
    public String FLOWCELL_BARCODE;
    
    @Option(doc = ReadStructure.PARAMETER_DOC, shortName = "RS")
    public String READ_STRUCTURE;

    @Option(doc = "Tab-separated file for creating all output fastqs demultiplexed by barcode for a lane with single " +
            "IlluminaBasecallsToFastq invocation.  The columns are OUTPUT_PREFIX, and BARCODE_1, BARCODE_2 ... BARCODE_X " +
            "where X = number of barcodes per cluster (optional).  Row with BARCODE_1 set to 'N' is used to specify " +
            "an output_prefix for no barcode match.",
            mutex = {"OUTPUT_PREFIX"})
    public File MULTIPLEX_PARAMS;

    @Option(doc = "Which adapters to look for in the read.")
    public List<IlluminaUtil.IlluminaAdapterPair> ADAPTERS_TO_CHECK = new ArrayList<IlluminaUtil.IlluminaAdapterPair>(
            Arrays.asList(IlluminaUtil.IlluminaAdapterPair.INDEXED,
                    IlluminaUtil.IlluminaAdapterPair.DUAL_INDEXED,
                    IlluminaUtil.IlluminaAdapterPair.NEXTERA_V2,
                    IlluminaUtil.IlluminaAdapterPair.FLUIDIGM));

    @Option(doc = "The number of threads to run in parallel. If NUM_PROCESSORS = 0, number of cores is automatically set to " +
            "the number of cores available on the machine. If NUM_PROCESSORS < 0, then the number of cores used will" +
            " be the number available on the machine less NUM_PROCESSORS.")
    public Integer NUM_PROCESSORS = 0;

    @Option(doc = "If set, this is the first tile to be processed (used for debugging).  Note that tiles are not processed" +
            " in numerical order.",
            optional = true)
    public Integer FIRST_TILE;

    @Option(doc = "If set, process no more than this many tiles (used for debugging).", optional = true)
    public Integer TILE_LIMIT;

    @Option(doc="Apply EAMSS filtering to identify inappropriately quality scored bases towards the ends of reads" +
            " and convert their quality scores to Q2.")
    public boolean APPLY_EAMSS_FILTER = true;

    @Option(doc = "If true, call System.gc() periodically.  This is useful in cases in which the -Xmx value passed " +
            "is larger than the available memory.")
    public Boolean FORCE_GC = true;

    @Option(doc = "Configure SortingCollections to store this many records before spilling to disk. For an indexed" +
            " run, each SortingCollection gets this value/number of indices.")
    public int MAX_READS_IN_RAM_PER_TILE = 1200000;

    @Option(doc="The minimum quality (after transforming 0s to 1s) expected from reads.  If qualities are lower than this value, an error is thrown." +
            "The default of 2 is what the Illumina's spec describes as the minimum, but in practice the value has been observed lower.")
    public int MINIMUM_QUALITY = BclQualityEvaluationStrategy.ILLUMINA_ALLEGED_MINIMUM_QUALITY;

    @Option(doc="Whether to include non-PF reads", shortName="NONPF", optional=true)
    public boolean INCLUDE_NON_PF_READS = true;

    @Option(doc="The read name header formatting to emit.  Casava1.8 formatting has additional information beyond Illumina, including: " +
            "the passing-filter flag value for the read, the flowcell name, and the sequencer name.", optional = false)
    public ReadNameFormat READ_NAME_FORMAT = ReadNameFormat.CASAVA_1_8;

    /** Simple switch to control the read name format to emit. */
    public enum ReadNameFormat {
        CASAVA_1_8, ILLUMINA
    }
    
    private final Map<String, FastqRecordsWriter> barcodeFastqWriterMap = new HashMap<String, FastqRecordsWriter>();
    private ReadStructure readStructure;
    IlluminaBasecallsConverter<FastqRecordsForCluster> basecallsConverter;
    private static final Log log = Log.getInstance(IlluminaBasecallsToFastq.class);
    private final FastqWriterFactory fastqWriterFactory = new FastqWriterFactory();
    private ReadNameEncoder readNameEncoder;
    private static final Comparator<FastqRecordsForCluster> queryNameComparator = new Comparator<FastqRecordsForCluster>() {
        @Override
        public int compare(final FastqRecordsForCluster r1, final FastqRecordsForCluster r2) {
            return SAMRecordQueryNameComparator.compareReadNames(r1.templateRecords[0].getReadHeader(),
                    r2.templateRecords[0].getReadHeader());
        }
    };


    @Override
    protected int doWork() {
        initialize();

        basecallsConverter.doTileProcessing();

        return 0;
    }

    @Override
    protected String[] customCommandLineValidation() {
        final LinkedList<String> errors = new LinkedList<String>();
        if (READ_NAME_FORMAT == ReadNameFormat.CASAVA_1_8 && MACHINE_NAME == null) {
            errors.add("MACHINE_NAME is required when using Casava1.8-style read name headers.");
        }

        if (READ_NAME_FORMAT == ReadNameFormat.CASAVA_1_8 && FLOWCELL_BARCODE == null) {
            errors.add("FLOWCELL_BARCODE is required when using Casava1.8-style read name headers.");
        }
        
        if (errors.isEmpty()) {
            return null;
        } else {
            return errors.toArray(new String[errors.size()]);
        }
    }

    /**
     * Prepares loggers, initiates garbage collection thread, parses arguments and initialized variables appropriately/
     */
    private void initialize() {
        fastqWriterFactory.setCreateMd5(CREATE_MD5_FILE);
        switch (READ_NAME_FORMAT) {
            case CASAVA_1_8:
                readNameEncoder = new Casava18ReadNameEncoder(MACHINE_NAME, RUN_BARCODE, FLOWCELL_BARCODE);        
                break;
            case ILLUMINA:
                readNameEncoder = new IlluminaReadNameEncoder(RUN_BARCODE);
                break;
        }
        
        final BclQualityEvaluationStrategy bclQualityEvaluationStrategy = new BclQualityEvaluationStrategy(MINIMUM_QUALITY);
        readStructure = new ReadStructure(READ_STRUCTURE);
        if (MULTIPLEX_PARAMS != null) {
            IOUtil.assertFileIsReadable(MULTIPLEX_PARAMS);
        }
        final boolean demultiplex;
        if (OUTPUT_PREFIX != null) {
            barcodeFastqWriterMap.put(null, buildWriter(OUTPUT_PREFIX));
            demultiplex = false;
        } else {
            populateWritersFromMultiplexParams();
            demultiplex = true;
        }
        final int readsPerCluster = readStructure.templates.length() + readStructure.barcodes.length();
        basecallsConverter = new IlluminaBasecallsConverter<FastqRecordsForCluster>(BASECALLS_DIR, LANE, readStructure,
                barcodeFastqWriterMap, demultiplex, MAX_READS_IN_RAM_PER_TILE/readsPerCluster, TMP_DIR, NUM_PROCESSORS,
                FORCE_GC, FIRST_TILE, TILE_LIMIT, queryNameComparator,
                new FastqRecordsForClusterCodec(readStructure.templates.length(),
                readStructure.barcodes.length()), FastqRecordsForCluster.class, bclQualityEvaluationStrategy,
                this.APPLY_EAMSS_FILTER, INCLUDE_NON_PF_READS);

        log.info("READ STRUCTURE IS " + readStructure.toString());

        basecallsConverter.setConverter(
		        new ClusterToFastqRecordsForClusterConverter(
				        basecallsConverter.getFactory().getOutputReadStructure()));

    }

    /**
     * Assert that expectedCols are present
     *
     * @param actualCols   The columns present in the MULTIPLEX_PARAMS file
     * @param expectedCols The columns that are REQUIRED
     */
    private void assertExpectedColumns(final Set<String> actualCols, final Set<String> expectedCols) {
        final Set<String> missingColumns = new HashSet<String>(expectedCols);
        missingColumns.removeAll(actualCols);

        if (missingColumns.size() > 0) {
            throw new PicardException(String.format(
                    "MULTIPLEX_PARAMS file %s is missing the following columns: %s.",
                    MULTIPLEX_PARAMS.getAbsolutePath(), StringUtil.join(", ", missingColumns
            )));
        }
    }

    /**
     * For each line in the MULTIPLEX_PARAMS file create a FastqRecordsWriter and put it in the barcodeFastqWriterMap map,
     * where the key to the map is the concatenation of all barcodes in order for the given line.
     */
    private void populateWritersFromMultiplexParams() {
        final TabbedTextFileWithHeaderParser libraryParamsParser = new TabbedTextFileWithHeaderParser(MULTIPLEX_PARAMS);

        final Set<String> expectedColumnLabels = CollectionUtil.makeSet("OUTPUT_PREFIX");
        final List<String> barcodeColumnLabels = new ArrayList<String>();
        for (int i = 1; i <= readStructure.barcodes.length(); i++) {
            barcodeColumnLabels.add("BARCODE_" + i);
        }

        expectedColumnLabels.addAll(barcodeColumnLabels);
        assertExpectedColumns(libraryParamsParser.columnLabels(), expectedColumnLabels);

        for (final TabbedTextFileWithHeaderParser.Row row : libraryParamsParser) {
            List<String> barcodeValues = null;

            if (barcodeColumnLabels.size() > 0) {
                barcodeValues = new ArrayList<String>();
                for (final String barcodeLabel : barcodeColumnLabels) {
                    barcodeValues.add(row.getField(barcodeLabel));
                }
            }

            final String key = (barcodeValues == null || barcodeValues.contains("N")) ? null : StringUtil.join("", barcodeValues);
            if (barcodeFastqWriterMap.containsKey(key)) {    //This will catch the case of having more than 1 line in a non-barcoded MULTIPLEX_PARAMS file
                throw new PicardException("Row for barcode " + key + " appears more than once in MULTIPLEX_PARAMS file " +
                        MULTIPLEX_PARAMS);
            }


            final FastqRecordsWriter writer = buildWriter(new File(row.getField("OUTPUT_PREFIX")));
            barcodeFastqWriterMap.put(key, writer);
        }
        if (barcodeFastqWriterMap.isEmpty()) {
            throw new PicardException("MULTIPLEX_PARAMS file " + MULTIPLEX_PARAMS + " does have any data rows.");
        }
        libraryParamsParser.close();
    }

    /**
     * @return FastqRecordsWriter that contains one or more FastqWriters (amount depends on read structure), all using
     * outputPrefix to determine the filename(s).
     */
    private FastqRecordsWriter buildWriter(final File outputPrefix) {
        final File outputDir = outputPrefix.getAbsoluteFile().getParentFile();
        IOUtil.assertDirectoryIsWritable(outputDir);
        final String prefixString = outputPrefix.getName();
        final FastqWriter[] templateWriters = new FastqWriter[readStructure.templates.length()];
        final FastqWriter[] barcodeWriters = new FastqWriter[readStructure.barcodes.length()];
        for (int i = 0; i < templateWriters.length; ++i) {
            final String filename = String.format("%s.%d.fastq", prefixString, i+1);
            templateWriters[i] = fastqWriterFactory.newWriter(new File(outputDir, filename));
        }
        for (int i = 0; i < barcodeWriters.length; ++i) {
            final String filename = String.format("%s.barcode_%d.fastq", prefixString, i+1);
            barcodeWriters[i] = fastqWriterFactory.newWriter(new File(outputDir, filename));
        }
        return new FastqRecordsWriter(templateWriters, barcodeWriters);
    }

    public static void main(final String[] args) {
        new IlluminaBasecallsToFastq().instanceMainWithExit(args);
    }

    /**
     * Container for various FastqWriters, one for each template read and one for each barcode read.
     */
    private static class FastqRecordsWriter implements IlluminaBasecallsConverter.ConvertedClusterDataWriter<FastqRecordsForCluster> {
        final FastqWriter[] templateWriters;
        final FastqWriter[] barcodeWriters;

        /**
         * @param templateWriters Writers for template reads in order, e,g. 0th element is for template read 1.
         * @param barcodeWriters Writers for barcode reads in order, e,g. 0th element is for barcode read 1.
         */
        private FastqRecordsWriter(final FastqWriter[] templateWriters, final FastqWriter[] barcodeWriters) {
            this.templateWriters = templateWriters;
            this.barcodeWriters = barcodeWriters;
        }

        @Override
        public void write(final FastqRecordsForCluster records) {
            write(templateWriters, records.templateRecords);
            write(barcodeWriters, records.barcodeRecords);
        }

        private void write(final FastqWriter[] writers, final FastqRecord[] records) {
            for (int i = 0; i < writers.length; ++i) {
                writers[i].write(records[i]);
            }
        }

        @Override
        public void close() {
            for (final FastqWriter writer : templateWriters) {
                writer.close();
            }
            for (final FastqWriter writer : barcodeWriters) {
                writer.close();
            }
        }
    }

    /**
     * Contains the results of transforming one cluster into the record(s) to be written to output file(s).
     */
    static class FastqRecordsForCluster {
        // These are accessed directly by converter and writer rather than through getters and setters.
        final FastqRecord[] templateRecords;
        final FastqRecord[] barcodeRecords;

        FastqRecordsForCluster(final int numTemplates, final int numBarcodes) {
            templateRecords = new FastqRecord[numTemplates];
            barcodeRecords = new FastqRecord[numBarcodes];
        }
    }

    /**
     * Passed to IlluminaBaseCallsConverter to do the conversion from input format to output format.
     */
    class ClusterToFastqRecordsForClusterConverter
            implements IlluminaBasecallsConverter.ClusterDataConverter<FastqRecordsForCluster> {

        private final int [] templateIndices;
        private final int [] barcodeIndices;

        ClusterToFastqRecordsForClusterConverter(final ReadStructure outputReadStructure) {
            this.templateIndices = outputReadStructure.templates.getIndices();
            this.barcodeIndices = outputReadStructure.barcodes.getIndices();
        }

        @Override
        public FastqRecordsForCluster convertClusterToOutputRecord(final ClusterData cluster) {
            final FastqRecordsForCluster ret = new FastqRecordsForCluster(readStructure.templates.length(), readStructure.barcodes.length());
            final boolean appendReadNumberSuffix = ret.templateRecords.length > 1;
            makeFastqRecords(ret.templateRecords, templateIndices, cluster, appendReadNumberSuffix);
            makeFastqRecords(ret.barcodeRecords, barcodeIndices, cluster, false);
            return ret;
        }

        private void makeFastqRecords(final FastqRecord[] recs, final int[] indices,
                                      final ClusterData cluster, final boolean appendReadNumberSuffix) {
            for (short i = 0; i < indices.length; ++i) {
                final ReadData readData = cluster.getRead(indices[i]);
                final String readBases = StringUtil.bytesToString(readData.getBases()).replace('.', 'N');
                final String readName = readNameEncoder.generateReadName(cluster, appendReadNumberSuffix ? i + 1 : null);
                recs[i] = new FastqRecord(
                        readName, 
                        readBases, 
                        null,
                        SAMUtils.phredToFastq(readData.getQualities())
                );
            }
        }
    }

    /**
     * Coded passed to IlluminaBasecallsConverter for use in SortingCollections of output records.
     */
    static class FastqRecordsForClusterCodec implements SortingCollection.Codec<FastqRecordsForCluster> {
        private final int numTemplates;
        private final int numBarcodes;
        private BasicFastqWriter writer = null;
        private FastqReader reader = null;

        FastqRecordsForClusterCodec(final int numTemplates, final int numBarcodes) {
            this.numTemplates = numTemplates;
            this.numBarcodes = numBarcodes;
        }

        @Override
        public void setOutputStream(final OutputStream os) {
            writer = new BasicFastqWriter(new PrintStream(os));
        }

        @Override
        public void setInputStream(final InputStream is) {
            reader = new FastqReader(new BufferedReader(new InputStreamReader(is)));
        }

        @Override
        public void encode(final FastqRecordsForCluster val) {
            if (numTemplates != val.templateRecords.length) throw new IllegalStateException();
            if (numBarcodes != val.barcodeRecords.length) throw new IllegalStateException();
            encodeArray(val.templateRecords);
            encodeArray(val.barcodeRecords);
            writer.flush();
        }

        private void encodeArray(final FastqRecord[] recs) {
            for (final FastqRecord rec: recs) {
                writer.write(rec);
            }
        }

        @Override
        public FastqRecordsForCluster decode() {
            if (!reader.hasNext()) return null;
            final FastqRecordsForCluster ret = new FastqRecordsForCluster(numTemplates, numBarcodes);
            decodeArray(ret.templateRecords);
            decodeArray(ret.barcodeRecords);
            return ret;
        }

        private void decodeArray(final FastqRecord[] recs) {
            for (int i = 0; i < recs.length; ++i) {
                recs[i] = reader.next();
            }
        }

        @Override
        public SortingCollection.Codec<FastqRecordsForCluster> clone() {
            return new FastqRecordsForClusterCodec(numTemplates, numBarcodes);
        }
    }
}
