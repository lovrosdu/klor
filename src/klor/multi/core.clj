(ns klor.multi.core
  (:require klor.multi.macros
            klor.multi.specials
            klor.multi.stdlib
            potemkin))

(potemkin/import-vars
 [klor.multi.macros defchor]
 [klor.multi.specials at local copy pack unpack* chor* inst]
 [klor.multi.stdlib move unpack chor])
