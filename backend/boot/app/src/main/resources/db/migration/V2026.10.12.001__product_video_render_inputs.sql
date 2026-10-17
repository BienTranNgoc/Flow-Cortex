UPDATE workflow_step
SET config = jsonb_build_object(
    'profile', 'vertical_ad_v1',
    'outputInputs', jsonb_build_array(jsonb_build_object(
        'stepOrder', 3,
        'outputName', 'speech.wav',
        'inputKey', 'speech'
    ))
)
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314';
