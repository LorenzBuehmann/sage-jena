package fr.gdd.sage;


import fr.gdd.sage.arq.OpExecutorSage;
import fr.gdd.sage.arq.QueryEngineSage;
import fr.gdd.sage.arq.SageConstants;
import fr.gdd.sage.datasets.Watdiv10M;
import fr.gdd.sage.io.SageInput;
import org.apache.jena.query.*;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.tdb2.solver.OpExecutorTDB2;
import org.apache.jena.tdb2.solver.QueryEngineTDB;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Optional;

@BenchmarkMode({Mode.SingleShotTime})
@Warmup(iterations = 5)
@State(Scope.Benchmark)
public class WatdivBenchmark {
    static Logger log = LoggerFactory.getLogger(WatdivBenchmark.class);

    static HashMap<String, Long> nbResultsPerQuery = new HashMap<>();

    @Param("sage-jena-benchmarks/queries/watdiv_with_sage_plan/query_10084.sparql")
    public String a_query;

    @Param({"default", "sage"})
    public String b_engine;

    @Param("target/watdiv10M")
    public String z_dbPath;


    @State(Scope.Benchmark)
    public static class Backend {
        volatile Dataset dataset;
        volatile QueryExecution queryExecution;
        volatile String query = null;
    }

    @Setup(Level.Trial)
    public void setup_engine(Backend b) {
        b.dataset = TDB2Factory.connectDataset(z_dbPath);
        if (!b.dataset.isInTransaction()) {
            b.dataset.begin(ReadWrite.READ);
        }

        if (b_engine.equals("default")) {
            QC.setFactory(b.dataset.getContext(), OpExecutorTDB2.OpExecFactoryTDB);
            QueryEngineTDB.register();
        } else {
            QC.setFactory(b.dataset.getContext(), new OpExecutorSage.OpExecutorSageFactory(ARQ.getContext()));
            QueryEngineSage.register();
        }
    }

    @TearDown(Level.Trial)
    public void setdown_engine(Backend b) {
        if (b_engine.equals("default")) {
            QueryEngineTDB.unregister();
        } else {
            QueryEngineSage.unregister();
        }
        if (b.dataset.isInTransaction()) {
            b.dataset.end();
        }
    }

    @Setup(Level.Trial)
    public void read_query(Backend b) {
        try {
            b.query = Files.readString(Paths.get(a_query), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.debug("{}", b.query);
    }

    @Setup(Level.Iteration)
    public void create_query_execution_plan(Backend b) {
        SageInput<?> input = new SageInput<>();
        Context c = b.dataset.getContext().copy().set(SageConstants.input, input);
        c.set(ARQ.optimization, false);

        try {
            b.queryExecution = QueryExecution.create()
                    .dataset(b.dataset)
                    .context(c)
                    .query(b.query).build();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Benchmark
    public long execute_query(Backend b) {
        long nbResults = 0;
        ResultSet rs = b.queryExecution.execSelect() ;
        while (rs.hasNext()) {
            rs.next();
            nbResults+=1;
        }

        // (TODO) remove this from the benchmarked part
        if (nbResultsPerQuery.containsKey(a_query)) {
            if (nbResultsPerQuery.get(a_query) != nbResults) {
                System.out.println("/!\\ not the same number of results");
            }
        } else {
            nbResultsPerQuery.put(a_query, nbResults);
        }

        log.debug("Got {} results for this query.", nbResults);

        return nbResults;
    }

    /**
     * Run the benchmark on Watdiv.
     * @param args [0] The path to the DB directory (default: "target").
     */
    public static void main(String[] args) throws RunnerException {
        Optional<String> dirPath_opt = (args.length > 0) ? Optional.of(args[0]) : Optional.empty();

        Watdiv10M watdiv = new Watdiv10M(dirPath_opt); // creates the db if need be

        String[] queriesAsArray = watdiv.queries.stream().map(p -> p.left).toArray(String[]::new);

        Options opt = new OptionsBuilder()
                .include(".*" + WatdivBenchmark.class.getSimpleName() + ".*")
                .param("a_query", queriesAsArray)
                .param("z_dbPath", watdiv.dbPath_asStr)
                .forks(1)
                .threads(1)
                .build();

        new Runner(opt).run();
    }

}
