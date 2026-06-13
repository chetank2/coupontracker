from extraction_eval.diff import compare_fields, FieldStatus

def test_match_when_normalized_values_equal():
    diff = compare_fields(
        expected={"storeName": "Kapiva", "redeemCode": "ABC123", "needsAttention": False},
        got={"storeName": "kapiva", "redeemCode": "ABC123", "needsAttention": False},
    )
    statuses = {d.field: d.status for d in diff}
    assert statuses["storeName"] == FieldStatus.MATCH
    assert statuses["redeemCode"] == FieldStatus.MATCH
    assert statuses["needsAttention"] == FieldStatus.MATCH

def test_missing_when_field_absent_in_got():
    diff = compare_fields(expected={"redeemCode": "X"}, got={})
    assert diff[0].field == "redeemCode"
    assert diff[0].status == FieldStatus.MISSING

def test_wrong_when_values_differ():
    diff = compare_fields(expected={"redeemCode": "X"}, got={"redeemCode": "Y"})
    assert diff[0].status == FieldStatus.WRONG

def test_extra_field_reported():
    diff = compare_fields(expected={}, got={"storeName": "X"})
    assert diff[0].field == "storeName"
    assert diff[0].status == FieldStatus.EXTRA

def test_description_normalization_ignores_quotes_and_extra_spaces():
    diff = compare_fields(
        expected={"description": 'Get Upto 40% Off* on Kapiva\'s "Strength and Stamina Range"'},
        got={"description": "Get Upto 40% Off* on Kapiva's  Strength and Stamina Range"},
    )
    assert diff[0].status == FieldStatus.MATCH

def test_expiry_date_normalization_matches_display_date_to_iso_date():
    diff = compare_fields(
        expected={"expiryDate": "2025-05-31"},
        got={"expiryDate": "31 May, 2025, 11:59 PM"},
    )
    assert diff[0].status == FieldStatus.MATCH
