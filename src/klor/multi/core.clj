(ns klor.multi.core
  (:require klor.multi.specials
            klor.multi.stdlib
            potemkin))

(potemkin/import-vars
 [klor.multi.specials at local copy pack unpack* chor* inst]
 [klor.multi.stdlib unpack chor move])
