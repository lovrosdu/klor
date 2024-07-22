(ns klor.multi.core
  (:require klor.multi.defchor
            klor.multi.specials
            klor.multi.stdlib
            potemkin))

(potemkin/import-vars
 [klor.multi.defchor defchor]
 [klor.multi.specials narrow lifting agree! copy pack inst]
 [klor.multi.stdlib move unpack chor bcast scatter scatter-seq gather])
