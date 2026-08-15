# Reliquary

Download a chosen *version* of a Steam game to a folder of your choosing.
Steam's client gives you one build: whatever is current. Reliquary gives you
the archive.

Not associated with or endorsed by Valve Corporation or Steam.

## Building

Needs JDK 26 (`JAVA_HOME`, or `/usr/lib/jvm/java-26-openjdk`) and the Clojure CLI.

    clojure -M:test                          # tests

    ./bin/setup-toolchain.sh                 # JavaFX jmods, for jlink
    clojure -T:build uber :omit-javafx true  # target/lib/reliquary.jar
    ./bin/package.sh                         # target/app/Reliquary/bin/Reliquary

`clojure -T:build uber` without `:omit-javafx` produces a fatter jar that runs
under a plain `java -jar`. `bin/package.sh` does not need that, because its
jlink runtime supplies JavaFX as modules.

## License

GPL-3.0-or-later. See LICENSE.
