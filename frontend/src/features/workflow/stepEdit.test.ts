import { ApiError } from '@flow-cortex/api-client';
import { changedFields, stepEditProblem, stepEditProblemOf } from './stepEdit';

describe('stepEditProblem', () => {
  it('maps a 400 naming fields[i].text to a field error under textarea i', () => {
    expect(stepEditProblem(400, undefined, { 'fields[1].text': 'Too long' })).toEqual({
      kind: 'field',
      messageKey: 'workflow.edit.error.field',
      fieldIndex: 1,
      detail: 'Too long',
    });
  });

  it('maps a 400 naming fields[i].pointer to the same textarea', () => {
    expect(stepEditProblem(400, undefined, { 'fields[0].pointer': 'Not editable' })).toMatchObject({
      kind: 'field',
      fieldIndex: 0,
      detail: 'Not editable',
    });
  });

  it.each(['fields', 'expectedRevision'])(
    'maps a 400 naming %s to a field error without an index',
    (key) => {
      expect(stepEditProblem(400, undefined, { [key]: 'Bad' })).toEqual({
        kind: 'field',
        messageKey: 'workflow.edit.error.field',
        fieldIndex: null,
        detail: 'Bad',
      });
    },
  );

  it('keeps a 400 that names nothing recognisable as a field error without text', () => {
    expect(stepEditProblem(400, undefined, undefined)).toEqual({
      kind: 'field',
      messageKey: 'workflow.edit.error.field',
      fieldIndex: null,
      detail: null,
    });
  });

  it('maps the three 409 codes and a 404', () => {
    expect(stepEditProblem(409, 'output_revision_conflict', undefined)).toMatchObject({
      kind: 'conflict',
      messageKey: 'workflow.edit.error.conflict',
    });
    expect(stepEditProblem(409, 'step_not_editable', undefined)).toMatchObject({
      kind: 'notEditable',
      messageKey: 'workflow.edit.error.notEditable',
    });
    expect(stepEditProblem(409, 'nothing_to_undo', undefined)).toMatchObject({
      kind: 'conflict',
      messageKey: 'workflow.edit.error.nothingToUndo',
    });
    expect(stepEditProblem(404, undefined, undefined)).toMatchObject({
      kind: 'gone',
      messageKey: 'workflow.edit.error.gone',
    });
  });

  it('treats an unknown 409 as a conflict to reread, and network, 5xx and other statuses as retry', () => {
    expect(stepEditProblem(409, 'something_else', undefined)).toMatchObject({ kind: 'conflict' });
    expect(stepEditProblem(undefined, undefined, undefined)).toMatchObject({
      kind: 'retry',
      messageKey: 'workflow.edit.error.retry',
    });
    expect(stepEditProblem(503, undefined, undefined)).toMatchObject({ kind: 'retry' });
    expect(stepEditProblem(429, undefined, undefined)).toMatchObject({ kind: 'retry' });
  });
});

describe('stepEditProblemOf', () => {
  it('reads status, code and errors from an API error', () => {
    const error = new ApiError(400, {
      status: 400,
      errors: { 'fields[2].text': 'Blank' },
    });

    expect(stepEditProblemOf(error)).toMatchObject({
      kind: 'field',
      fieldIndex: 2,
      detail: 'Blank',
    });
    expect(
      stepEditProblemOf(new ApiError(409, { status: 409, code: 'output_revision_conflict' })),
    ).toMatchObject({ kind: 'conflict' });
  });

  it('reads a failure that is not an API error as a network failure', () => {
    expect(stepEditProblemOf(new TypeError('Failed to fetch'))).toMatchObject({ kind: 'retry' });
  });
});

describe('changedFields', () => {
  const fields = [
    { pointer: '/segments/0/vi', text: 'Xin chao' },
    { pointer: '/segments/1/vi', text: 'Cam on' },
    { pointer: '/segments/2/vi', text: 'Tam biet' },
  ];

  it('returns only the fields whose draft differs, in field order', () => {
    expect(
      changedFields(fields, { '/segments/2/vi': 'Hen gap lai', '/segments/0/vi': 'Chao ban' }),
    ).toEqual([
      { pointer: '/segments/0/vi', text: 'Chao ban' },
      { pointer: '/segments/2/vi', text: 'Hen gap lai' },
    ]);
  });

  it('returns nothing when every draft equals the current text or is absent', () => {
    expect(changedFields(fields, {})).toEqual([]);
    expect(changedFields(fields, { '/segments/1/vi': 'Cam on' })).toEqual([]);
  });

  it('counts a draft cleared to blank as a change', () => {
    expect(changedFields(fields, { '/segments/1/vi': '' })).toEqual([
      { pointer: '/segments/1/vi', text: '' },
    ]);
  });
});
