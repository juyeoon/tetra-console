import SearchOffRoundedIcon from '@mui/icons-material/SearchOffRounded';
import PageHeader from '@/components/layout/PageHeader';
import StateMessage from '@/components/common/StateMessage';

interface Props {
  breadcrumbs: string[];
  back: { to: string; label: string };
  message?: string;
}

export default function NotFoundView({ breadcrumbs, back, message = '페이지를 찾을 수 없습니다' }: Props) {
  return (
    <>
      <PageHeader breadcrumbs={breadcrumbs} back={back} title="상세" />
      <StateMessage icon={<SearchOffRoundedIcon />} tone="grey" title={message} description="삭제되었거나 잘못된 주소입니다." />
    </>
  );
}
