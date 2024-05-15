(ns klor.multi.core
  (:require klor.multi.defchor
            klor.multi.specials
            klor.multi.stdlib
            potemkin))

(potemkin/import-vars
 [klor.multi.defchor defchor]
 [klor.multi.specials narrow local copy pack unpack* chor* inst]
 [klor.multi.stdlib move unpack chor])
