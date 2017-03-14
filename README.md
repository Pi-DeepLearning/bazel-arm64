# [Bazel](http://bazel.build) ([Beta](http://bazel.build/roadmap.html#beta))

*{Fast, Correct} - Choose two*

Bazel is a build tool that builds code quickly and reliably. It is used to build
the majority of Google's software, and thus it has been designed to handle
build problems present in Google's development environment, including:

* **A massive, shared code repository, in which all software is built from
source.** Bazel has been built for speed, using both caching and parallelism
to achieve this. Bazel is critical to Google's ability to continue
to scale its software development practices as the company grows.

* **An emphasis on automated testing and releases.** Bazel has
been built for correctness and reproducibility, meaning that a build performed
on a continuous build machine or in a release pipeline will generate
bitwise-identical outputs to those generated on a developer's machine.

* **Language and platform diversity.** Bazel's architecture is general enough to
support many different programming languages within Google, and can be
used to build both client and server software targeting multiple
architectures from the same underlying codebase.

Find more background about Bazel in our [FAQ](http://bazel.build/faq.html).

## Getting Started

  * How to [install Bazel](http://bazel.build/docs/install.html)
  * How to [get started using Bazel](http://bazel.build/docs/getting-started.html)
  * The Bazel command line is documented in the  [user manual](http://bazel.build/docs/bazel-user-manual.html)
  * The rule reference documentation is in the [build encyclopedia](http://bazel.build/docs/be/overview.html)
  * How to [use the query command](http://bazel.build/docs/query.html)
  * How to [extend Bazel](http://bazel.build/docs/skylark/index.html)
  * The test environment is described on the page [writing tests](http://bazel.build/docs/test-encyclopedia.html)

## About the Bazel project

  * How to [contribute to Bazel](http://bazel.build/contributing.html)
  * Our [governance plan](http://bazel.build/governance.html)
  * Future plans are in the [roadmap](http://bazel.build/roadmap.html)
  * For each feature, which level of [support](http://bazel.build/support.html) to expect
  * [Current Bazel Release Candidate](https://github.com/bazelbuild/bazel/issues?utf8=%E2%9C%93&q=is%3Aopen%20label%3A%22Release%20blocker%22%20label%3A%22type%3A%20process%22)

[![Build Status](http://ci.bazel.io/buildStatus/icon?job=bazel-tests)](http://ci.bazel.io/job/bazel-tests)
