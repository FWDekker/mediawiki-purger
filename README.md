# MediaWiki Purger
Purge all pages on a MediaWiki wiki using a CLI tool.

## Requirements
Java 8 or newer.

## Usage
1. Download the jar on [the releases page](https://github.com/FWDekker/mediawiki-purger/releases).
2. Open a terminal and navigate to where you downloaded the jar.
3. Run the jar with `java -jar mediawiki-purger.jar [options]`. See below for a list of options.

### Options
```
Usage: purger [OPTIONS]

Options:
  --api URL          The URL to the MediaWiki API, such as
                     https://www.mediawiki.org/w/api.php.
  --page-size INT    Amount of pages to purge at a time.
  --throttle INT...  The maximum amount of API requests per time period in
                     milliseconds, such as `10 1000` for 10 requests per
                     second. Note that each purge requires two requests.
  --startFrom TEXT   Starts purging pages in alphabetical order starting from
                     this page title. Does not have to refer to an existing
                     page.
  -h, --help         Show this message and exit

Process finished with exit code 0
```

## Development
```bash
gradlew assemble  # Create the jar in the `build/libs` directory
```
