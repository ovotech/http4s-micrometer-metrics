# Http4s metrics implementation based on micrometer

This is an implementation of [http4s](https://http4s.org/s) metrics based on [micrometer](http://micrometer.io).

## Metrics names

This module record the following meters:

- Timer `default.response-time`
- Timer `default.response-headers-time`
- Gauge `default.active-requests`

The `default.response-time` timer has the `status-code`, `method` and `termination` tags.
The `default.response-headers-time` timer has the `method` tag.
The `default.active-requests` does not have any tag.

In addition to this tags, each metric will record the global tags set in the Config.

It is also possible to set a prefix for the metrics name using the `prefix` configuration setting.

The `default` name can be customized using a classifier function. With the same classifier function is possible to record additional tags using this syntax: `classifier[tag1:value1,tag2:value2,tag3:value3]`. The classifier part can be blanc as well as the tags part can be empty.

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
