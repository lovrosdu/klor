(ns klor.core
  (:require
   klor.defchor
   klor.specials
   klor.stdlib
   potemkin))

(potemkin/import-vars
 [klor.defchor defchor]
 [klor.specials narrow lifting agree! copy pack inst]
 [klor.stdlib move unpack chor bcast scatter scatter-seq gather])
