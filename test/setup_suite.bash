setup_suite() {
  DERIVATIONS="$BATS_TMPDIR/.cljnix-derivations"
  export DERIVATIONS
  rm -f "$DERIVATIONS"
}
