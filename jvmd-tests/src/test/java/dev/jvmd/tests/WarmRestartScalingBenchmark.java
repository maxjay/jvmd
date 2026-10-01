package dev.jvmd.tests;

import java.nio.file.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Strict task A11 / W5 scaling gate: going from 1,000 to 5,000 units multiplies the no-change warm
 * restart wall time by at most 6, and both sizes stay within {@code 2 x files + directories} stats
 * with zero directory enumerations and zero bytes hashed.
 *
 * Run: {@code mvn -pl jvmd-tests test -Dtest=WarmRestartScalingBenchmark -DexcludedGroups=corpus}
 */
@Tag("benchmark")
class WarmRestartScalingBenchmark {
    @TempDir Path small,large;

    private WarmRestartGateTest.Restart measure(Path root,int units)throws Exception{
        var gate=new WarmRestartGateTest();gate.root=root;
        var restart=gate.measure(SyntheticProjects.generate(SyntheticProjects.Topology.RANDOM_DAG,units,SyntheticProjects.SEED));gate.closeDocuments();
        System.out.printf("A11 units=%d javac=%d restores=%d stats=%d (bound %d) enumerations=%d bytesHashed=%d wall=%.1f ms%n",
                units,restart.javac(),restart.restores(),restart.stats(),2L*restart.files()+restart.directories(),
                restart.enumerations(),restart.bytesHashed(),restart.nanos()/1e6);
        assertThat(restart.javac()).isZero();
        assertThat(restart.restores()).isEqualTo(units);
        assertThat(restart.enumerations()).isZero();
        assertThat(restart.bytesHashed()).isZero();
        assertThat(restart.stats()).isLessThanOrEqualTo(2L*restart.files()+restart.directories());
        return restart;
    }

    @Test void warmRestartScalesLinearlyFromOneToFiveThousandUnits()throws Exception{
        var thousand=measure(small,1_000);
        var fiveThousand=measure(large,5_000);
        double ratio=(double)fiveThousand.nanos()/thousand.nanos();
        System.out.printf("A11 wall ratio 5000/1000 = %.2f%n",ratio);
        assertThat(ratio).as("A11 wall-time ratio").isLessThanOrEqualTo(6.0);
    }
}
