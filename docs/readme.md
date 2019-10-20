# Purpose

The election library provides a simple way to create a system with *High Availability* & *Redundancy* in mind. It will automatically handle network partitioning and master election which is common to distributed systems. In general, distributed systems use a specific process to handle that (Zookeeper is the most common one). By providing a similar tool as library, we allow projects to not depend of yet-another-thirdparty process.

This library implements the well-known Paxos algorithm for election, but does not implement the centralized data storage part, as we think it would complicate the system without any big added value. If you want a centralized data store, you should rather use a database designed for it (like MongoDB, HBase, ...).

# Requirements

RAM: \~10MB, CPU: <<1 core, stable network

Required resources for the library are quite limited, as we only have a few loaded classes in memory, and the process is quite simple so CPU is not important as well. It's also difficult to estimate the required resources as there is the overhead of the JVM itself.

We assume a relatively stable network and correct time synchronization between servers (with ntp or similar). Time synchronization does not need to be perfect, even a few seconds of difference is okay, but it is not recommended to have more than that.

The cluster size should not impact drastically the RAM or CPU usage.

As we use the Paxos algorithm, you should only have 3 or 5 nodes participating in the election. The nodes not allowed to participate in the election will simply be followers all the time.

# Installation

The library is available on Maven Central, hence, you can easily import it:
```
libraryDependencies += "net.degols.libs" % "election" % "1.0.0" exclude("log4j", "log4j") exclude("org.slf4j","slf4j-log4j12")
```

Another alternative, useful for contributors, is to have the "election" package in the same directory as your "election-example" project using it. Then, by following this [build.sbt](https://github.com/gilles-degols/election-example/blob/master/build.sbt) used for an election-example project, you can automatically build the election library alongside the election-example project itself.

# Usage

The best way to see how to use the library is to follow the code example provided [here](https://github.com/gilles-degols/election-example). After checking the code, familiarize yourself with the different steps describe below.

1. Set up your application.conf to indicate the default nodes taking part in the election process. You should only provide 3 or 5 nodes for the Paxos algorithm to be efficient (so, with 2n+1 nodes in your system, you can accept the failure of n nodes) .

   ```
   election.nodes = ["node1:2182", "node2:2182", "node3:2182"]
   election.akka.remote.netty.tcp.hostname = node1
   election.akka.remote.netty.tcp.port = 2182
   ```

   Note that your process might never be elected as master if the provided hostname & port is not part of the election nodes above.\
2. Create a *Worker* class, which will receive a message every time the leader changes, it also has the possibility to check the current status with a few methods in the parent class. \
   This *Worker* must extends *ElectionWrapper *, be a Singleton and must be started at boot. Code example is available for the [Worker](https://github.com/gilles-degols/election-example/blob/master/app/net/degols/example/election/Worker.scala) as well as the way to [start it at boot](https://github.com/gilles-degols/election-example/blob/master/app/Module.scala).

Once you have everything set up, it's up to you to handle the election changes as you will receive the following messages (after they were analyzed by the ElectionWrapper): `IAmLeader, IAmFollower, TheLeaderIs`

Obviously the current election process is quite simple and should only be used if you have a few actions to execute. If you want to handle many different actions, and so, manage a cluster in itself, you should follow the [Cluster library](https://github.com/gilles-degols/cluster).