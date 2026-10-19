import DescriptionRoundedIcon from '@mui/icons-material/DescriptionRounded';
import PlaceholderPanel from '@/components/common/PlaceholderPanel';

/** Tetra Portal의 '신청 요약' 화면을 재사용할 영역 */
export default function ApplicationContentPlaceholder() {
  return (
    <PlaceholderPanel
      icon={<DescriptionRoundedIcon />}
      title="신청 내용"
      minHeight={520}
      lines={[
        <>
          이 영역은 <b>Tetra Portal &gt; 이벤트 신청 &gt; 신청 요약</b>과 동일한 내용입니다.
        </>,
        'Portal 화면 컴포넌트가 확정되면 동일하게 연결됩니다.',
      ]}
    />
  );
}
