import Link from 'next/link';

const APP_NAME = 'Roadtrip';
const SEARCH_PATH = '/search';

export default function HomePage() {
  return (
    <main>
      <h1>{APP_NAME}</h1>
      <nav>
        <Link href={SEARCH_PATH}>Search campgrounds</Link>
      </nav>
    </main>
  );
}
