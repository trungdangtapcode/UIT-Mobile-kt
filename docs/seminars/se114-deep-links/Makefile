TECTONIC ?= tectonic

.PHONY: pdf
pdf:
	mkdir -p build
	$(TECTONIC) --keep-logs --outdir build main.tex
	cp build/main.pdf build/SE114-Deep-Links.pdf
