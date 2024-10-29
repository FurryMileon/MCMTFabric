package net.himeki.mcmtfabric.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import net.himeki.mcmtfabric.parallelised.ThreadedChunksRange;

import java.util.ArrayList;
import java.util.List;

@Config(name = "mcmt-threaded-ranges")
public class ThreadedRangesConfig implements ConfigData {
    public List<ThreadedChunksRange> threadedRanges = new ArrayList<>();
}
