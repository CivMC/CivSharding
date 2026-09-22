plugins {
    alias(libs.plugins.paper.userdev)
}

version = "2.2.2"

dependencies {
    // Local chat carries over a shard border. Compile only: where Shards is not installed the relay
    // is never looked up and chat stops at the edge of the server, as it always did
    compileOnly(project(":libraries:shards-api"))
    paperweight {
        paperDevBundle(libs.versions.paper)
    }

    compileOnly(project(":plugins:civmodcore-paper"))
    compileOnly(project(":plugins:banstick-paper"))
    compileOnly(project(":plugins:namelayer-paper"))
    compileOnly(libs.placeholderapi)
}
