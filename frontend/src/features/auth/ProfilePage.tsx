import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, authHeaders, unwrap, updateProfile } from '@flow-cortex/api-client';
import type { ProfileUpdateRequest } from '@flow-cortex/api-client';
import { profileSchema, type ProfileFormValues } from './profileSchema';
import { useMeQuery } from './useAuth';

/** Fix my own display name; email/role/status are not self-service. */
export function ProfilePage() {
  const meQuery = useMeQuery();
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    // `exactOptionalPropertyTypes` rejects `values: undefined` — omit the key
    // entirely until the query resolves, rather than setting it to undefined.
    ...(meQuery.data ? { values: { name: meQuery.data.name ?? '' } } : {}),
  });

  const updateMutation = useMutation({
    mutationFn: (values: ProfileUpdateRequest) => unwrap(updateProfile(values, authHeaders())),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });

  if (meQuery.isLoading) {
    return <output>Loading…</output>;
  }
  if (meQuery.isError || !meQuery.data) {
    return <p role="alert">Could not load your profile.</p>;
  }

  const onSubmit = handleSubmit((values) => updateMutation.mutate(values));
  const serverError =
    updateMutation.error instanceof ApiError ? updateMutation.error.problem?.detail : undefined;

  return (
    <main className="profile-page">
      <h1>My profile</h1>
      <dl>
        <dt>Email</dt>
        <dd>{meQuery.data.email}</dd>
        <dt>Role</dt>
        <dd>{meQuery.data.role}</dd>
      </dl>
      <form onSubmit={onSubmit} noValidate aria-label="Edit profile">
        <div className="field">
          <label htmlFor="profile-name">Display name</label>
          <input id="profile-name" {...register('name')} />
          {errors.name && <p role="alert">{errors.name.message}</p>}
        </div>
        {serverError && <p role="alert">{serverError}</p>}
        {updateMutation.isSuccess && <output>Saved.</output>}
        <button type="submit" disabled={updateMutation.isPending}>
          {updateMutation.isPending ? 'Saving…' : 'Save'}
        </button>
      </form>
    </main>
  );
}
