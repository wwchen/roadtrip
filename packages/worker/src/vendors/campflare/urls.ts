const CAMPGROUND_URL_PREFIX = 'https://campflare.com/campground';

export function campflareCampgroundUrl(campgroundId: string): string {
  return `${CAMPGROUND_URL_PREFIX}/${campgroundId}`;
}
