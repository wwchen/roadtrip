package ca.floo.roadtrip.repo

// SQL fragment that returns TRUE when a JSONB payload matches one of the
// known vendor provider-ref shapes. Used to rank the "provider" vendor_ref
// ahead of catalog-provenance refs when multiple refs point at the same
// canonical row. Vendor precedence and their discriminator keys live in
// ProviderRefParser (single source of truth); keep this list aligned when
// a new vendor lands.
internal fun providerRefShapeSql(payloadExpression: String): String =
    """
    (
      jsonb_exists($payloadExpression, 'recgov_id')
      OR jsonb_exists($payloadExpression, 'campflare_id')
      OR (jsonb_exists($payloadExpression, 'mapId') AND jsonb_exists($payloadExpression, 'transactionLocationId'))
      OR jsonb_exists($payloadExpression, 'park_id')
      OR jsonb_exists($payloadExpression, 'facility_id')
      OR jsonb_exists($payloadExpression, 'place_id')
    )
    """.trimIndent()
