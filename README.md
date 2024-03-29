# Deprecated

*This functionality has now been merged into [Meters4s](https://github.com/ovotech/meters4s) as `meters4s-http4s`*


This is an implementation of [http4s](https://http4s.org/s) metrics based on [meters4s](https://github.com/ovotech/meters4s).

## Installation

```sbt
libraryDependencies += "com.kaluza" %% "http4s-meters4s-metrics" % "<latestVersion>"
```

## Metrics names

This module records the following meters:

- Timer `default.response-time`
- Timer `default.response-headers-time`
- Gauge `default.active-requests`

The `default.response-time` timer has the `status-code`, `method` and `termination` tags.
The `default.response-headers-time` timer has the `method` tag.
The `default.active-requests` does not have any tag.

In addition to these tags, each metric will record the global tags set in the Config.

It is also possible to set a prefix for the metrics name using the `prefix` configuration setting.

The `default` name can be customised using a classifier function. With the same classifier function, it is possible to record additional tags using this syntax: `classifier[tag1:value1,tag2:value2,tag3:value3]`. The classifier part can be blank as well as the tags part can be empty.

The standard tags values are the following:

- statusCode
  - 2xx
  - 3xx
  - 4xx
  - 5xx

- method
  - head
  - get
  - put
  - patch
  - post
  - delete
  - options
  - move
  - trace
  - connect  
  - other

- termination
  - normal
  - abnormals
  - error
  - timeout
