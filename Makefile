.PHONY: help deps install deploy test clean

SHELL       := /bin/bash
export PATH := bin:launch4j:$(PATH)
# export LEIN_SNAPSHOTS_IN_RELEASE := yes

green        = '\e[0;32m'
nc           = '\e[0m'

version      = $(shell grep ^version version.properties |sed 's/.*=//')
verfile      = version.properties
bootbin      = $(PWD)/bin/boot.sh
bootexe      = $(PWD)/bin/boot.exe
distjar      = $(PWD)/bin/boot.jar
loaderjar    = target/boot-loader.jar
loadersource = boot/loader/src/boot/Loader.java
bootjar      = boot/boot/target/boot-$(version).jar
aetherjar    = boot/aether/target/aether-$(version).jar
aetheruber   = aether.uber.jar
workerjar    = boot/worker/target/worker-$(version).jar
podjar       = target/boot-pod.jar
corejar      = target/boot-core.jar
basejar      = target/boot-base.jar
baseuber     = target/boot-base-$(version)-uber.jar
alljars      = $(podjar) $(aetherjar) $(workerjar) $(corejar) $(baseuber) $(bootjar)

help:
	@echo "version =" $(version)
	@echo "Usage: make {help|deps|install|deploy|test|clean}" 1>&2 && false

clean:
	(cd boot/base && mvn -q clean && rm -f src/main/resources/$(aetheruber))
	# (cd boot/core && lein clean)
	# (cd boot/aether && lein clean)
	# (cd boot/pod && lein clean)
	# (cd boot/worker && lein clean)
	# (cd boot/loader && make clean)

# bloop:
# 	which lein

# bin/lein:
# 	mkdir -p bin
# 	wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein -O bin/lein
# 	chmod 755 bin/lein

# deps: bin/lein

$(loaderjar): $(loadersource)
	(boot loader)

$(bootjar): $(verfile) boot/boot/project.clj
	(boot transaction-jar install)

# boot/base/pom.xml: $(verfile) boot/base/pom.in.xml
# 	(cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml)

$(basejar): $(shell find boot/base/src)
	(boot base install)

$(podjar): $(verfile) boot/pod/project.clj $(shell find boot/pod/src)
	(boot pod install)

$(aetherjar): $(verfile) boot/aether/project.clj $(podjar) $(shell find boot/aether/src)
	(boot aether install)

$(workerjar): $(verfile) boot/worker/project.clj $(shell find boot/worker/src)
	(boot worker install)

$(corejar): $(verfile) boot/core/project.clj $(shell find boot/core/src)
	(boot core install)

$(baseuber): $(shell find boot/base/src/main)
	(boot base --uberjar)
	cp $(baseuber) $(distjar)
	# FIXME: this is just for testing -- remove before release
	mkdir -p $$HOME/.boot/cache/bin/$(version)
	cp $(baseuber) $$HOME/.boot/cache/bin/$(version)/boot.jar
	# End testing code -- cut above.

$(bootbin):
	(boot build-bin)
	cp target/boot.sh $(bootbin)
	@echo -e "\033[0;32m<< Created boot executable: $(bootbin) >>\033[0m"

launch4j-config.xml: launch4j-config.in.xml $(verfile)
	sed -e "s@__VERSION__@`cat $(verfile) |sed 's/.*=//'`@" launch4j-config.in.xml > launch4j-config.xml;

$(bootexe): $(loaderjar) launch4j-config.xml
	@if [ -z $$RUNNING_IN_CI ] && which launch4j; then \
		launch4j launch4j-config.xml; \
		echo -e "\033[0;32m<< Created boot executable: $(bootexe) >>\033[0m"; \
		[ -e $(bootexe) ] && touch $(bootexe); \
	else true; fi

.installed: $(basejar) $(alljars) $(bootbin) $(bootexe)
	date > .installed

install: .installed

.deployed: .installed
	(boot base push-release)
	#(cd boot/base   && lein deploy clojars boot/base $(version) target/base-$(version).jar pom.xml)
	(boot pod push-release)
	# (cd boot/pod    && lein deploy clojars)
	(boot aether push-release)
	# (cd boot/aether && lein deploy clojars)
	(boot worker push-release)
	# (cd boot/worker && lein deploy clojars)
	(boot core push-release)
	# (cd boot/core   && lein deploy clojars)
	(boot transaction-jar push-release)
	# (cd boot/boot   && lein deploy clojars)
	date > .deployed

deploy: .deployed

test:
	echo "<< no tests yet >>"
