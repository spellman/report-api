-   [Overview](#overview)
-   [Running the app](#running-the-app)
    -   [Running locally](#running-locally)
    -   [Running on Heroku](#running-on-heroku)
    -   [Running tests](#running-tests)
-   [Choices I made in building the app](#choices-i-made)
    -   [Development process](#development-process)
    -   [Built from components](#built-from-components)
    -   [Designed to be adaptable](#designed-to-be-adaptable)
    -   [No number formatting for output](#number-format)
    -   [Testing](#testing)
-   [Possible further work](#possible-further-work)
    -   [Improved error handling](#improved-error-handling)
    -   [Additional testing](#additional-testing)
-   [Reloaded workflow and components
    references](#reloaded-and-components)

<a name="overview"></a>Overview
-------------------------------

I wanted to explore:

-   Building a web app with [Clojure](http://clojure.org/) and [Ring](https://github.com/ring-clojure/ring)
-   Using [Stuart Sierra's Reloaded workflow and Component framework](#reloaded-and-components)

I built a web API to generate reports from two fixed datasets containing information about the same orders. The API should have a single route and accept various values for an `order_by` URL parameter, which will determine the sort-order of the data in the report.


<a name="running-the-app"></a>Running the App
---------------------------------------------

### <a name="running-locally"></a>Running locally

-   From the REPL, evaluate `(user/dev)` and then `(reset)`.  
     The app should be available at <http://localhost:3000>.

    **If you generated an uberjar, remove it before starting the REPL: `lein do clean, repl`.**

-   From the command line with Leiningen, enter `lein run` to use the default port of 5000, or  
     `lein run <port number>` to specify a port.  
     The app should be available at <http://localhost:5000> or on the port you specified.

-   From the command line, create an uberjar and run it:

    -   Enter `lein do clean, uberjar`.

    -   Enter `java -jar target/report-api-standalone.jar` to use the default port of 5000, or  
         `java -jar target/report-api-standalone.jar <port number>` to specify a port.

    The app should be available at <http://localhost:5000> or on the port you specified.

### <a name="running-on-heroku"></a>Running on Heroku

The app is live on Heroku at <https://calm-forest-3839.herokuapp.com/>. (I have a free account so the first request will likely be slow.)

### <a name="running-tests"></a>Running tests

-   From the REPL, evaluate `(clojure.test/run-tests 'report-api.core-test)`.

-   From the command line, run `lein test`.

<a name="choices-i-made"></a> Choices I Made In Building the App
----------------------------------------------------------------

### <a name="development-process"></a>Development Process — Always Have a Running System

I began by building a skeleton app with a single component that managed the web server; it responded to any request with a fixed response. I deployed the app to [Heroku](https://calm-forest-3839.herokuapp.com/) to make sure it would run in a production environment. Then I built out the required functionality — converting requests and data files into JSON-format reports. Then I added tests, docstrings, and additional functionality: logging, error-handling, and more thorough sorting.

### <a name="built-from-components"></a>Build the App From Components

I structured the app as a set of components that are assembled, started, and stopped via [Stuart Sierra's Component framework for dependency injection](#reloaded-and-components). I tried to factor each functional piece of the application into a component. I tried to isolate components by only using their public APIs.

Currently, the app consists of the following components:

-   App - manages the web server and uses the Web App and the Logger
-   Logger - normally Timbre but I made a core.async-based logger for
    testing
-   Web App - coordinates the Parser, Processor, and Reporter to
    generate the expected report data
-   Parser - parses data from the given order-data files
-   Processor - summarizes orders
-   Reporter - generates the expected report data, which ring-json
    middleware then converts to JSON

### <a name="designed-to-be-adaptable"></a>Design the App To Be Adaptable

Why do this in an app this small and in an initial iteration?

On the one hand, [YAGNI](https://en.wikipedia.org/wiki/You_aren%27t_gonna_need_it).

On the other hand,

-   In order to explore building an app with components, I need components.
-   The requirements seem most likely to be changed along certain axes so I'll break up functionality into components such that change along those axes is easy.

#### Sort Orderings

The application is to return a report, sorted according to one of three values of the `order_by` URL parameter: `session-type-desc`, `order-id-asc`, and `unit-price-dollars-asc`. I interpreted these orderings as a white-list of permitted orderings:

-   The Web App component is constructed with the permitted orderings in
    `report-api.system/system`.
-   I think the format of URL params (hyphen-separated-words) is likely
    to be stable relative to the other requirements so I included
    parsing the `order_by` param in the Web App.

#### Data Files

Since the set of files to be processed into reports is known and fixed, the Web App component is constructed with the configuration data to parse the files. If the data sources were changed from files readable with `clojure.core/slurp` or if the data sources became unknown until runtime, then the Web App component would need to be changed accordingly.

The `report-api.core/parse-nice-dsv` Parser component handles “nice” delimiter-separated-values data — where the delimiter does not appear in any of the header or record values and where all rows have the same number of elements. The parser takes a configuration with each dataset, allowing the parser to handle different delimiters, column orders, and formats in the record-row fields. On the other hand, if the shape of the input data changes, then `report-api.core/parse-nice-dsv` would need to be swapped for a different Parser.

#### Data Processing

The datasets are currently summarized by summing the values corresponding to certain keys. Different processing could be done by a different Processor. Additionally, processors are composable.

#### Report Format or Content

A report is currently in JSON format and contains order summaries and sorted order data. Another report could be generated by a different Reporter component.

### <a name="number-format"></a>No Number Formatting for Output

I did not format the numbers in the JSON output. I believe JSON doesn't distinguish between integers and floating point numbers and I expect that the client will format the data. Since the numbers represent money, I parsed and operated on them as BigDecimals. I let ring-json output them as JSON with its default formatting — it looks like numbers with no fractional part become integers in the JSON.

### <a name="testing"></a>Testing

I used traditional-style `clojure.test/deftest` tests to check that the three given `order_by` param values were mapped to the respective expected outputs. I initially tested the API with `clojure.java.shell/sh` and cURL but I switched to clj-http in order to easily check the response status code and content-type as well as the response body.

I then used `test.check` generative tests to test the application error-handling and most of the Parser functionality. The upshot was that I simplified the format of the Parser-config data and later recognized that I could remove some parsing code.

I also added preconditions and postconditions to various functions. See [Possible Further Work](#possible-further-work) below.

<a name="possible-further-work"></a> Possible Further Work
----------------------------------------------------------

### <a name="improved-error-handling"></a>Improved Error Handling

I don't seem to be able to stop the web server after an error/exception is thrown, which means the JVM must be restarted — I would like to fix this. The ring handler runs in the try block of the `report-api.core/wrap-error-handling` middleware. Errors seem to be logged but I lose the ability to stop the system. When I evaluate `(reset)` at the REPL, which should stop and restart the web server, I get `BindException Address already in use  sun.nio.ch.Net.bind0 (Net.java:-2)`. Similarly, the applicable tests run in try blocks and finally clauses are in place to stop the respective test systems after the tests run. Either those finally clauses are not running or they can't stop the system just like I can't stop the development system from the REPL.

I've read <https://docs.oracle.com/javase/tutorial/essential/exceptions/finally.html> and <http://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions>. Catch clauses in the tests have not solved the problem.

`report-api.core-test/t-web-app-error-yields-error-json` sets up a test system with a Web App component that throws an `Exception` when it runs. This test runs without a problem. The test system in play does not use Jetty, though, so I would start trying to solve this problem by investigating how Jetty works and how it handles errors.

### <a name="additional-testing"></a>Additional Testing

The app could be tested more extensively. I'm fairly confident in the application at this point (I hope I should be, lol) but I could add tests for the sorting and summary functions. I really like the idea of design by contract but I tend to start thinking about contracts in terms of data shapes or types. I would look into Prismatic/schema in order to represent those sorts of contracts and I might need to read some Eiffel or something in order to see how people are thinking about contracts in general.

<a name="reloaded-and-components"></a>Reloaded Workflow and Components References
---------------------------------------------------------------------------------

I used the following sources in implementing the Reloaded workflow and components:

Components with Component framework

-   "Components — Just Enough Structure", 2014 presentation by Stuart Sierra at Clojure/West  
     <https://www.youtube.com/watch?v=13cmHf_kt-Q>
-   Component  
     <https://github.com/stuartsierra/component>

Reloaded workflow and components without Component framework

-   "Clojure in the Large", 2013 presentation by Stuart Sierra at Clojure/West about components  

    <http://www.infoq.com/presentations/Clojure-Large-scale-patterns-techniques>
-   Episode 32, Think Relevance Podcast, 2013 discussion with Stuart Sierra about his Reloaded workflow  
     <http://thinkrelevance.com/blog/2013/05/29/stuart-sierra-episode-032>
-   "My Clojure Workflow, Reloaded", 2013 article by Stuart Sierra about his Reloaded workflow and companion piece to the above podcast and presentation  
     <http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded>
-   "Stuart Sierra's Clojure Development Workflow", 2013 article by Simon Katz about his implementation of the Reloaded workflow and components  

    <http://nomistech.blogspot.co.uk/2013/06/stuart-sierras-clojure-development_18.html>  
     <https://github.com/simon-katz/clojure-workflow-demo>
